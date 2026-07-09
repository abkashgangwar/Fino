package com.ember.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.jboss.logging.Logger;

/**
 * One lazily-connected SFTP session/channel pair, with liveness checking and
 * transparent reconnect. Not thread-safe by design - {@link SftpObjectService}
 * hands out instances of this one-at-a-time via a blocking queue, which is what
 * keeps concurrent callers from corrupting a shared channel's state.
 * <p>
 * Takes a {@link SftpEndpointConfig} rather than a concrete config type, since the
 * input and output sides are now two independent SFTP servers ({@link SftpInputConfig}
 * / {@link SftpOutputConfig}) - this class only needs the connection fields they share,
 * not which side it's connecting to.
 */
final class SftpConnection {

    private static final Logger LOG = Logger.getLogger(SftpConnection.class);
    private static final int TIMEOUT_MS = 10_000;

    private final SftpEndpointConfig endpointConfig;
    private Session session;
    private ChannelSftp channel;

    SftpConnection(SftpEndpointConfig endpointConfig) {
        this.endpointConfig = endpointConfig;
    }

    /**
     * Returns this connection's channel, (re)connecting first if it isn't currently
     * live (idle timeout, network blip, SFTP server restart) - callers don't need
     * to know or care.
     */
    ChannelSftp getChannel() throws Exception {
        if (channel != null && channel.isConnected() && session != null && session.isConnected()) {
            return channel;
        }
        closeQuietly(); // clean up any half-open state before reconnecting

        JSch jsch = new JSch();
        if (endpointConfig.privateKeyPath().isPresent()) {
            jsch.addIdentity(endpointConfig.privateKeyPath().get());
        }

        session = jsch.getSession(endpointConfig.username(), endpointConfig.host(), endpointConfig.port());
        endpointConfig.password().ifPresent(session::setPassword);
        // Local/test SFTP server only - a real deployment should pin the host key instead.
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(TIMEOUT_MS);

        channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(TIMEOUT_MS);
        // Raise JSch's request-pipelining depth above its conservative default (16) -
        // see SftpEndpointConfig.bulkRequests() for why this matters for large-file throughput.
        channel.setBulkRequests(endpointConfig.bulkRequests());
        LOG.debugf("Opened SFTP session/channel to %s:%d", endpointConfig.host(), endpointConfig.port());

        return channel;
    }

    void closeQuietly() {
        try {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        } catch (Exception ignored) {
            // best-effort cleanup
        }
        try {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        } catch (Exception ignored) {
            // best-effort cleanup
        }
        channel = null;
        session = null;
    }
}
