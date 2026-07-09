package com.ember.sftp;

import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.Optional;

/**
 * Connection fields shared by both SFTP servers this service talks to.
 * <p>
 * Deliberately NOT annotated with {@code @ConfigMapping} itself - it exists purely to
 * be extended by {@link SftpInputConfig} and {@link SftpOutputConfig}, which each apply
 * their own prefix ("sftp.input" / "sftp.output") and can override any member (e.g. to
 * give host/port/username different per-server defaults). SmallRye Config supports this
 * "a config mapping can extend another interface and inherit/override its members"
 * pattern directly, so the two concrete configs below share this shape without
 * duplicating every field twice.
 */
public interface SftpEndpointConfig {

    String host();

    int port();

    String username();

    /** Password auth - fine for local testing. Prefer a private key for anything real. */
    Optional<String> password();

    /** Path to a private key file, used instead of / in addition to password auth. */
    @WithName("private-key-path")
    Optional<String> privateKeyPath();

    /** Directory (as seen by the SFTP client) this side of the pipeline operates on. */
    String dir();

    /**
     * Number of concurrent SFTP session/channel pairs kept in this server's pool.
     * <p>
     * JSch's ChannelSftp is NOT thread-safe, so concurrent workers each need their own
     * channel - see {@code SftpObjectService}'s pool for how these are borrowed/returned
     * safely. Keep both the input and output pool sizes >= app.processing.parallelism so
     * every concurrent worker can always get one connection of each kind.
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
