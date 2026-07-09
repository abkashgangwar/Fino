package com.ember.sftp;

import com.ember.storage.FileStorageService;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
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
 * Talks to TWO INDEPENDENT SFTP SERVERS - {@link SftpInputConfig} for the server files
 * are picked up from, and {@link SftpOutputConfig} for the (possibly entirely different)
 * server the encrypted output is written to. This is a deliberate split, not just a
 * naming convention: different host, port, credentials, even different vendor are all
 * expected. Every operation below already picked a single pool per direction (see point
 * 2 below), so plugging in a second real server only meant giving {@link #inputPool} and
 * {@link #outputPool} their own {@link SftpConnection}s built from their own config,
 * rather than both being built from one shared config as before.
 * <p>
 * Supports BOTH pipeline modes off the same connection pools, switched purely by
 * {@code app.processing.streaming-enabled} in {@code FileProcessingService}:
 * <p>
 * 1. POOLED CONNECTIONS instead of one shared channel. JSch's ChannelSftp is NOT
 *    thread-safe, so concurrent workers each need their own channel - borrowed from
 *    {@link #inputPool}/{@link #outputPool} below.
 * <p>
 * 2. TWO pools rather than one. Streaming a file through {@link #streamProcess} means
 *    one connection's InputStream (get) and a *different* connection's OutputStream
 *    (put) need to be open and interleaved at the same time, in the same worker
 *    thread - a single ChannelSftp channel isn't meant to have a get() and a put()
 *    both mid-flight on it simultaneously. Pulling both connections for a transfer
 *    out of one shared pool would risk deadlock under load: if every worker grabs one
 *    connection first and then blocks waiting for a second, and the pool is exactly
 *    parallelism-sized, every worker can end up holding one and waiting forever for
 *    another. Two independent pools - one only ever used for reading (against the
 *    input server), one only ever used for writing (against the output server) - make
 *    that impossible by construction, and now also happen to point at two different
 *    physical servers.
 *    <p>
 *    The file-based (non-streaming) methods below only ever need ONE connection at a
 *    time each, so they simply pick a pool per operation (inputPool for reads,
 *    outputPool for writes) - reusing the exact same two pools rather than needing a
 *    third pooling strategy for that mode.
 * <p>
 * 3. FILE-TO-FILE STREAMING ({@link #downloadFromInput}/{@link #uploadToProcessed})
 *    for the non-streaming mode, and TRUE END-TO-END STREAMING ({@link #streamProcess})
 *    for the streaming mode - see {@code FileProcessingService} for how the two modes
 *    wire up zip + PGP-encryption differently around these.
 * <p>
 * 4. DONE-FOLDER IDEMPOTENCY instead of a per-file output check. A fully processed
 *    file is renamed (metadata-only, not re-uploaded) into a "done" sub-directory
 *    under the input location - on the INPUT server, via {@link #moveToDone} - so it
 *    simply stops appearing in {@link #listInputObjects}'s (non-recursive) listing.
 * <p>
 * Sizing: {@link #inputPool} is sized to {@code sftp.input.pool-size} and
 * {@link #outputPool} to {@code sftp.output.pool-size} - they no longer have to match.
 * Keep BOTH >= app.processing.parallelism so every concurrent worker can always get one
 * connection of each kind.
 */
@ApplicationScoped
public class SftpObjectService implements FileStorageService {

    private static final Logger LOG = Logger.getLogger(SftpObjectService.class);
    private static final long BORROW_TIMEOUT_SECONDS = 30;
    /** Sub-directory under sftp.input.dir (on the input server) that fully-processed source files are moved into. */
    private static final String DONE_SUBDIR = "done";

    @Inject
    SftpInputConfig inputConfig;

    @Inject
    SftpOutputConfig outputConfig;

    /** Connections used exclusively for reading (listing, get(), and other metadata ops) - all against the input server. */
    private BlockingQueue<SftpConnection> inputPool;
    /** Connections used exclusively for writing (put(), rename(), rm()) - all against the output server. Kept
     *  separate from inputPool (and pointed at a different server) so a streamProcess() call can never deadlock
     *  waiting on its own pool - see the class-level javadoc above. */
    private BlockingQueue<SftpConnection> outputPool;

    @PostConstruct
    void initPool() {
        int inputSize = Math.max(1, inputConfig.poolSize());
        int outputSize = Math.max(1, outputConfig.poolSize());
        inputPool = new LinkedBlockingQueue<>(inputSize);
        outputPool = new LinkedBlockingQueue<>(outputSize);
        for (int i = 0; i < inputSize; i++) {
            inputPool.add(new SftpConnection(inputConfig));
        }
        for (int i = 0; i < outputSize; i++) {
            outputPool.add(new SftpConnection(outputConfig));
        }
        LOG.infof("SFTP connection pools initialized: %d read slot(s) against %s:%d, %d write slot(s) against %s:%d",
                inputSize, inputConfig.host(), inputConfig.port(),
                outputSize, outputConfig.host(), outputConfig.port());
    }

    @Override
    public void initialize() throws Exception {
        withConnection(inputPool, ch -> {
            ensureDir(ch, inputConfig.dir());
            ensureDir(ch, inputConfig.dir() + "/" + DONE_SUBDIR);
            return null;
        });
        withConnection(outputPool, ch -> {
            ensureDir(ch, outputConfig.dir());
            return null;
        });
    }

    @Override
    public List<String> listInputObjects() throws Exception {
        return withConnection(inputPool, ch -> {
            List<String> keys = new ArrayList<>();
            Vector<ChannelSftp.LsEntry> entries = ch.ls(inputConfig.dir());
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
        withConnection(inputPool, ch -> {
            // get(String, OutputStream) streams straight to the local file, same as before -
            // but unlike get(String, String), it does NOT call _stat() on the remote file
            // first (that overload stats the source to preserve permissions/mtime on the
            // local copy, which we don't need). One less round-trip per file, and it
            // sidesteps a JSch response-parsing crash (IndexOutOfBoundsException in
            // ChannelSftp.fill/_stat) seen under heavy concurrent load against a
            // resource-constrained test SFTP server.
            try (OutputStream out = Files.newOutputStream(destination)) {
                ch.get(inputConfig.dir() + "/" + key, out);
            }
            return null;
        });
    }

    @Override
    public void uploadToProcessed(String key, Path source) throws Exception {
        withConnection(outputPool, ch -> {
            // put(String, String) streams straight from the local file - no in-memory buffer.
            ch.put(source.toString(), outputConfig.dir() + "/" + key);
            return null;
        });
    }

    @Override
    public void streamProcess(String inputKey, String outputKey, StreamTransform transform) throws Exception {
        String inputPath = inputConfig.dir() + "/" + inputKey;
        String outputPath = outputConfig.dir() + "/" + outputKey;

        SftpConnection inConn = borrow(inputPool, "read");
        SftpConnection outConn;
        try {
            outConn = borrow(outputPool, "write");
        } catch (Exception e) {
            inputPool.put(inConn); // don't leak the read connection we already grabbed
            throw e;
        }

        try {
            ChannelSftp inChannel = inConn.getChannel();
            ChannelSftp outChannel = outConn.getChannel();

            try (InputStream remoteIn = inChannel.get(inputPath);
                 OutputStream remoteOut = outChannel.put(outputPath)) {
                transform.transform(remoteIn, remoteOut);
            } catch (Exception e) {
                // Best-effort: remove whatever partial bytes made it to the destination
                // (streams above are already closed at this point) so a half-written
                // .zip.pgp is never mistaken for a complete, valid one.
                try {
                    outChannel.rm(outputPath);
                } catch (Exception cleanupEx) {
                    LOG.debugf(cleanupEx, "Could not clean up partial output '%s' after failure (non-fatal).", outputPath);
                }
                throw e;
            }
        } finally {
            inputPool.put(inConn);
            outputPool.put(outConn);
        }
    }

    @Override
    public void moveToDone(String key) throws Exception {
        withConnection(inputPool, ch -> {
            String from = inputConfig.dir() + "/" + key;
            String to = inputConfig.dir() + "/" + DONE_SUBDIR + "/" + key;
            // rename() is a single metadata-only SFTP request (SSH_FXP_RENAME) - no file
            // content is re-transferred, so this is cheap no matter how large the file was.
            ch.rename(from, to);
            return null;
        });
    }

    @Override
    public boolean existsInProcessed(String key) throws Exception {
        // Checks the OUTPUT server, so this must borrow from outputPool, not inputPool -
        // now that input/output are two different servers, inputPool's connections simply
        // can't see anything living on the output server.
        return withConnection(outputPool, ch -> {
            try {
                ch.lstat(outputConfig.dir() + "/" + key);
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
        withConnection(inputPool, ch -> {
            ch.rm(inputConfig.dir() + "/" + key);
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
     * Borrows a connection from the given pool, runs the action, and always returns
     * the connection afterwards - even on failure - so a broken/mid-error channel
     * isn't lost from the pool. A connection that turns out to be dead is transparently
     * reconnected the next time it's borrowed (see SftpConnection#getChannel).
     */
    private <T> T withConnection(BlockingQueue<SftpConnection> pool, SftpAction<T> action) throws Exception {
        SftpConnection conn = borrow(pool, pool == inputPool ? "read" : "write");
        try {
            return action.run(conn.getChannel());
        } finally {
            pool.put(conn);
        }
    }

    private SftpConnection borrow(BlockingQueue<SftpConnection> pool, String kind) throws InterruptedException {
        SftpConnection conn = pool.poll(BORROW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (conn == null) {
            boolean isInput = pool == inputPool;
            int size = isInput ? inputConfig.poolSize() : outputConfig.poolSize();
            String property = isInput ? "sftp.input.pool-size" : "sftp.output.pool-size";
            throw new IllegalStateException(
                    "Timed out waiting for a free SFTP " + kind + " connection (all "
                            + size + " in use). Consider raising " + property + ".");
        }
        return conn;
    }

    /** Cleanly close every pooled connection when the application shuts down. */
    @PreDestroy
    void onShutdown() {
        for (SftpConnection conn : inputPool) {
            conn.closeQuietly();
        }
        for (SftpConnection conn : outputPool) {
            conn.closeQuietly();
        }
    }
}
