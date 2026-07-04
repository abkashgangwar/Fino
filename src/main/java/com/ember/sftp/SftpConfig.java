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
}
