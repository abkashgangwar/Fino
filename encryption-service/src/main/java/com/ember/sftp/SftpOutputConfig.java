package com.ember.sftp;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Config for the SFTP server the encrypted output is written TO (the output side).
 * <p>
 * Kept as a separate {@code @ConfigMapping} from {@link SftpInputConfig} - see that
 * class's javadoc for why. Only the property prefix ("sftp.output" here) and the
 * defaults below differ; the connection-field shape itself comes from
 * {@link SftpEndpointConfig}.
 * <p>
 * Defaults match the "output" SFTP container in docker-compose.yml.
 */
@ConfigMapping(prefix = "sftp.output")
public interface SftpOutputConfig extends SftpEndpointConfig {

    @Override
    @WithDefault("localhost")
    String host();

    @Override
    @WithDefault("2223")
    int port();

    @Override
    @WithDefault("testuser2")
    String username();

    /** Directory (as seen by the SFTP client) encrypted output is written to. */
    @Override
    @WithDefault("/upload/output")
    String dir();
}
