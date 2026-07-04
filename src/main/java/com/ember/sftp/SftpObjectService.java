package com.ember.sftp;

import com.ember.storage.FileStorageService;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

/**
 * SFTP-backed implementation of {@link FileStorageService}. Reuses a single
 * session/channel across calls instead of opening a fresh one per operation.
 *
 * Previously, every download/upload/delete opened its own SSH session -
 * fine at low volume, but under load (e.g. a poll cycle draining hundreds of
 * queued files) that meant 3 full SSH handshakes PER FILE. The handshake's
 * key exchange (ECDH/DH key generation) is CPU-heavy enough that it was
 * tripping Quarkus's blocked-thread watchdog ("Thread blocked for 60000+ ms")
 * during large batches.
 *
 * The channel is now opened lazily on first use and kept open for reuse.
 * getChannel() checks liveness before handing it out and transparently
 * reconnects if the session/channel was closed (idle timeout, network blip,
 * SFTP server restart) - callers don't need to know or care.
 */
@ApplicationScoped
public class SftpObjectService implements FileStorageService {

    private static final Logger LOG = Logger.getLogger(SftpObjectService.class);
    private static final int TIMEOUT_MS = 10_000;

    @Inject
    SftpConfig sftpConfig;

    private Session session;
    private ChannelSftp channel;

    @Override
    public void initialize() throws Exception {
        ChannelSftp ch = getChannel();
        ensureDir(ch, sftpConfig.inputDir());
        ensureDir(ch, sftpConfig.outputDir());
    }

    @Override
    public List<String> listInputObjects() throws Exception {
        ChannelSftp ch = getChannel();
        List<String> keys = new ArrayList<>();
        Vector<ChannelSftp.LsEntry> entries = ch.ls(sftpConfig.inputDir());
        for (ChannelSftp.LsEntry entry : entries) {
            String name = entry.getFilename();
            if (!entry.getAttrs().isDir() && !name.startsWith(".")) {
                keys.add(name);
            }
        }
        return keys;
    }

    @Override
    public byte[] downloadFromInput(String key) throws Exception {
        ChannelSftp ch = getChannel();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ch.get(sftpConfig.inputDir() + "/" + key, out);
            return out.toByteArray();
        }
    }

    @Override
    public void uploadToProcessed(String key, byte[] data) throws Exception {
        ChannelSftp ch = getChannel();
        try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            ch.put(in, sftpConfig.outputDir() + "/" + key);
        }
    }

    @Override
    public void deleteFromInput(String key) throws Exception {
        ChannelSftp ch = getChannel();
        ch.rm(sftpConfig.inputDir() + "/" + key);
    }

    private void ensureDir(ChannelSftp ch, String dir) throws SftpException {
        String[] parts = dir.split("/");
        StringBuilder path = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            path.append("/").append(part);
            try {
                ch.lstat(path.toString());
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    ch.mkdir(path.toString());
                    LOG.infof("Created SFTP directory '%s'", path);
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Returns the shared channel, (re)connecting first if it isn't currently
     * live. Synchronized as a safety net - the scheduler only ever invokes
     * pollAndProcess() from one thread at a time (concurrentExecution =
     * SKIP), but this guards against any other caller (e.g. a health check)
     * racing a reconnect.
     */
    private synchronized ChannelSftp getChannel() throws Exception {
        if (channel != null && channel.isConnected() && session != null && session.isConnected()) {
            return channel;
        }
        closeQuietly(); // clean up any half-open state before reconnecting

        JSch jsch = new JSch();
        if (sftpConfig.privateKeyPath().isPresent()) {
            jsch.addIdentity(sftpConfig.privateKeyPath().get());
        }

        session = jsch.getSession(sftpConfig.username(), sftpConfig.host(), sftpConfig.port());
        sftpConfig.password().ifPresent(session::setPassword);
        // Local/test SFTP server only - a real deployment should pin the host key instead.
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(TIMEOUT_MS);

        channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(TIMEOUT_MS);
        LOG.info("Opened SFTP session/channel (will be reused for subsequent calls)");

        return channel;
    }

    private void closeQuietly() {
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        channel = null;
        session = null;
    }

    /** Cleanly close the shared connection when the application shuts down. */
    @PreDestroy
    void onShutdown() {
        closeQuietly();
    }
}