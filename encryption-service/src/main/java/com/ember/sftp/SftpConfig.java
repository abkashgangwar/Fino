package com.ember.sftp;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.Optional;

/**
 * Config for the SFTP backend - the only file transport this service uses.
 * Defaults below match the local test SFTP server in docker-compose.yml.
 */
@ConfigMapping(prefix = "sftp")
public interface SftpConfig {

    @WithDefault("localhost")
    String host();

    @WithDefault("2222")
    int port();

    @WithDefault("testuser")
    String username();

    /** Password auth - fine for local testing. Prefer a private key for anything real. */
    Optional<String> password();

    /** Path to a private key file, used instead of / in addition to password auth. */
    @WithName("private-key-path")
    Optional<String> privateKeyPath();

    /** Directory (as seen by the SFTP client) files are picked up from. */
    @WithName("input-dir")
    @WithDefault("/upload/input")
    String inputDir();

    /** Directory encrypted output is written to. */
    @WithName("output-dir")
    @WithDefault("/upload/output")
    String outputDir();

    /**
     * Number of concurrent SFTP session/channel pairs kept in the pool.
     * <p>
     * Previously the service reused a single shared channel for every operation.
     * That's fine at low volume, but JSch's ChannelSftp is NOT thread-safe, so once
     * files were processed in parallel (or two poll cycles overlapped), concurrent
     * calls on that one channel corrupted its state and blew up the batch. The pool
     * gives each concurrent worker its own channel, borrowed/returned safely via a
     * blocking queue - raise this together with app.processing.parallelism.
     */
    @WithName("pool-size")
    @WithDefault("8")
    int poolSize();

    /**
     * How many SFTP read/write requests JSch is allowed to have "in flight"
     * (unacknowledged) at once per channel - i.e. request pipelining depth.
     * <p>
     * JSch's own internal default is a conservative 16. On any link with real
     * round-trip latency (not localhost), each in-flight request slot is what lets
     * the next chunk go out before the previous one's ack comes back - too few
     * slots means large-file transfer throughput is capped by latency instead of
     * bandwidth, no matter how fast the link actually is. Raising this (64 here)
     * lets more requests queue up in flight, which matters a lot when transferring
     * many large files (e.g. 1M-row CSVs) rather than small ones. Lower it if a
     * particular SFTP server implementation pushes back on high concurrency.
     */
    @WithName("bulk-requests")
    @WithDefault("64")
    int bulkRequests();
}
