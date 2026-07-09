package com.ember.sftp;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Config for the SFTP server files are picked up FROM (the input side).
 * <p>
 * This is a separate server from {@link SftpOutputConfig} on purpose - upstream systems
 * often drop files onto one SFTP server while the encrypted output is expected on a
 * completely different one (different host, credentials, even different vendor). Both
 * configs share their connection-field shape via {@link SftpEndpointConfig}; only the
 * property prefix ("sftp.input" here) and the defaults below differ.
 * <p>
 * Defaults match the "input" SFTP container in docker-compose.yml.
 */
@ConfigMapping(prefix = "sftp.input")
public interface SftpInputConfig extends SftpEndpointConfig {

    @Override
    @WithDefault("localhost")
    String host();

    @Override
    @WithDefault("2222")
    int port();

    @Override
    @WithDefault("testuser")
    String username();

    /** Directory (as seen by the SFTP client) files are picked up from. */
    @Override
    @WithDefault("/upload/input")
    String dir();
}
