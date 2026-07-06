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
 */
final class SftpConnection {

    private static final Logger LOG = Logger.getLogger(SftpConnection.class);
    private static final int TIMEOUT_MS = 10_000;

    private final SftpConfig sftpConfig;
    private Session session;
    private ChannelSftp channel;

    SftpConnection(SftpConfig sftpConfig) {
        this.sftpConfig = sftpConfig;
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
        // Raise JSch's request-pipelining depth above its conservative default (16) -
        // see SftpConfig.bulkRequests() for why this matters for large-file throughput.
        channel.setBulkRequests(sftpConfig.bulkRequests());
        LOG.debug("Opened SFTP session/channel");

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
