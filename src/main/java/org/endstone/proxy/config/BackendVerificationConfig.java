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

    /**
     * Ten minutes. Sized for the resource-pack download the verifier's callback waits behind, not for
     * the handshake &mdash; it was 15 seconds, and players downloading a few megabytes of packs beat
     * that routinely and were turned away with "Proxy verification failed."
     *
     * <p>Named rather than inlined so the value a running proxy can compare itself against is the same
     * one {@code ProxyConfig} writes into a generated config.
     */
    public static final long DEFAULT_PENDING_JOIN_TTL_MILLIS = 600_000L;

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
