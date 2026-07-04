package com.ember.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;

import java.util.Optional;

@ConfigMapping(prefix = "pgp")
public interface PgpConfig {

    PublicKeyConfig publicKey();

    /**
     * NOTE: There is deliberately NO bundled/classpath key here. The public key
     * must always come from outside the built artifact (mounted file or an
     * injected env var) so it never ends up baked into source control or the jar.
     */
    interface PublicKeyConfig {

        /** Filesystem path to the key file, e.g. a mounted Secret volume. */
        @WithName("path")
        Optional<String> path();

        /** Raw ASCII-armored key content, e.g. injected directly as an env var / Secret. */
        @WithName("content")
        Optional<String> content();
    }
}
