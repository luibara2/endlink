package org.endstone.proxy.config;

import java.net.InetSocketAddress;

public record BackendVerificationConfig(
        boolean enabled,
        InetSocketAddress listenAddress,
        String sharedSecret,
        long pendingJoinTtlMillis,
        long requestSkewMillis
) {
    public static final String DEFAULT_SHARED_SECRET = "change-this-shared-secret";

    public BackendVerificationConfig {
        if (listenAddress == null) {
            throw new IllegalArgumentException("listenAddress cannot be null");
        }
        if (sharedSecret == null || sharedSecret.isBlank()) {
            throw new IllegalArgumentException("sharedSecret cannot be blank");
        }
        if (pendingJoinTtlMillis < 1) {
            throw new IllegalArgumentException("pendingJoinTtlMillis must be positive");
        }
        if (requestSkewMillis < 1) {
            throw new IllegalArgumentException("requestSkewMillis must be positive");
        }
    }
}
