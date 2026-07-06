package com.ember.sftp;

import com.ember.storage.FileStorageService;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * SFTP-backed implementation of {@link FileStorageService}.
 * <p>
 * Three changes from the original single-channel version, all driven by what broke
 * under load (2000+ queued files, 100k-row CSVs):
 * <p>
 * 1. POOLED CONNECTIONS instead of one shared channel. JSch's ChannelSftp is NOT
 *    thread-safe. The old code relied on the scheduled poll method never running
 *    concurrently with itself - but the {@code @Scheduled} annotation never actually
 *    set {@code concurrentExecution = SKIP} (Quarkus defaults to PROCEED), so once a
 *    poll cycle over a big backlog ran longer than the cron interval, the *next*
 *    cron tick started a second overlapping call that raced the first on the same
 *    channel. That's the corruption/crash under load. Now each concurrent worker
 *    borrows its own {@link SftpConnection} from a bounded pool (sftp.pool-size)
 *    and returns it when done, so concurrent use is safe by construction, and
 *    FileProcessingService's scheduled method is also now marked
 *    concurrentExecution = SKIP as defense in depth.
 * <p>
 * 2. FILE-TO-FILE STREAMING instead of byte[] buffering. get()/put() now stream
 *    directly to/from local disk paths, so a 100k-row file never has to be held
 *    fully in JVM heap.
 * <p>
 * 3. DONE-FOLDER IDEMPOTENCY instead of a per-file output check. The pipeline used
 *    to call existsInProcessed() for every input file, every poll cycle - an SFTP
 *    round-trip per file, forever, since source files are never deleted. At 100,000+
 *    files that overhead alone could run into minutes per cycle. Now a fully
 *    processed file is renamed (metadata-only, not re-uploaded) into a "done"
 *    sub-directory under the input location via {@link #moveToDone}, so it simply
 *    stops appearing in {@link #listInputObjects}'s (non-recursive) listing - turning
 *    N round-trips into zero for anything already done.
 */
@ApplicationScoped
public class SftpObjectService implements FileStorageService {

    private static final Logger LOG = Logger.getLogger(SftpObjectService.class);
    private static final long BORROW_TIMEOUT_SECONDS = 30;
    /** Sub-directory under sftp.input-dir that fully-processed source files are moved into. */
    private static final String DONE_SUBDIR = "done";

    @Inject
    SftpConfig sftpConfig;

    private BlockingQueue<SftpConnection> pool;

    @PostConstruct
    void initPool() {
        int size = Math.max(1, sftpConfig.poolSize());
        pool = new LinkedBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            pool.add(new SftpConnection(sftpConfig));
        }
        LOG.infof("SFTP connection pool initialized with %d slot(s)", size);
    }

    @Override
    public void initialize() throws Exception {
        withConnection(ch -> {
            ensureDir(ch, sftpConfig.inputDir());
            ensureDir(ch, sftpConfig.outputDir());
            ensureDir(ch, sftpConfig.inputDir() + "/" + DONE_SUBDIR);
            return null;
        });
    }

    @Override
    public List<String> listInputObjects() throws Exception {
        return withConnection(ch -> {
            List<String> keys = new ArrayList<>();
            Vector<ChannelSftp.LsEntry> entries = ch.ls(sftpConfig.inputDir());
            for (ChannelSftp.LsEntry entry : entries) {
                String name = entry.getFilename();
                if (!entry.getAttrs().isDir() && !name.startsWith(".")) {
                    keys.add(name);
                }
            }
            return keys;
        });
    }

    @Override
    public void downloadFromInput(String key, Path destination) throws Exception {
        withConnection(ch -> {
            // get(String, String) streams straight to the local file - no in-memory buffer.
            ch.get(sftpConfig.inputDir() + "/" + key, destination.toString());
            return null;
        });
    }

    @Override
    public void uploadToProcessed(String key, Path source) throws Exception {
        withConnection(ch -> {
            // put(String, String) streams straight from the local file - no in-memory buffer.
            ch.put(source.toString(), sftpConfig.outputDir() + "/" + key);
            return null;
        });
    }

    @Override
    public void moveToDone(String key) throws Exception {
        withConnection(ch -> {
            String from = sftpConfig.inputDir() + "/" + key;
            String to = sftpConfig.inputDir() + "/" + DONE_SUBDIR + "/" + key;
            // rename() is a single metadata-only SFTP request (SSH_FXP_RENAME) - no file
            // content is re-transferred, so this is cheap no matter how large the file was.
            ch.rename(from, to);
            return null;
        });
    }

    @Override
    public boolean existsInProcessed(String key) throws Exception {
        return withConnection(ch -> {
            try {
                ch.lstat(sftpConfig.outputDir() + "/" + key);
                return true;
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    return false;
                }
                throw e;
            }
        });
    }

    @Override
    public void deleteFromInput(String key) throws Exception {
        withConnection(ch -> {
            ch.rm(sftpConfig.inputDir() + "/" + key);
            return null;
        });
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

    @FunctionalInterface
    private interface SftpAction<T> {
        T run(ChannelSftp channel) throws Exception;
    }

    /**
     * Borrows a connection from the pool, runs the action, and always returns the
     * connection afterwards - even on failure - so a broken/mid-error channel isn't
     * lost from the pool. A connection that turns out to be dead is transparently
     * reconnected the next time it's borrowed (see SftpConnection#getChannel).
     */
    private <T> T withConnection(SftpAction<T> action) throws Exception {
        SftpConnection conn = pool.poll(BORROW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (conn == null) {
            throw new IllegalStateException(
                    "Timed out waiting for a free SFTP connection from the pool (all "
                            + sftpConfig.poolSize() + " in use). Consider raising sftp.pool-size.");
        }
        try {
            return action.run(conn.getChannel());
        } finally {
            pool.put(conn);
        }
    }

    /** Cleanly close every pooled connection when the application shuts down. */
    @PreDestroy
    void onShutdown() {
        for (SftpConnection conn : pool) {
            conn.closeQuietly();
        }
    }
}
