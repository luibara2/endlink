package org.endstone.proxy.config;

/**
 * How hard the proxy tries when moving a player to another backend.
 *
 * <p>Retries exist because the common reason a switch fails is that the target is restarting, not
 * that it is gone. They run silently for {@link #retryWindowMillis}: a player who asked to be moved
 * wants to be moved, not given a running commentary on the attempts.</p>
 */
public record BackendSwitchConfig(
        long retryWindowMillis,
        long retryDelayMillis,
        long timeoutMillis,
        long connectTimeoutMillis
) {
    public BackendSwitchConfig {
        if (retryWindowMillis < 0) {
            throw new IllegalArgumentException("retryWindowMillis cannot be negative");
        }
        if (retryDelayMillis < 0) {
            throw new IllegalArgumentException("retryDelayMillis cannot be negative");
        }
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        if (connectTimeoutMillis < 1) {
            throw new IllegalArgumentException("connectTimeoutMillis must be positive");
        }
    }

    public static BackendSwitchConfig defaults() {
        // 30s of retrying, roughly every 8s once a 5s dial-out failure is counted in — long enough
        // to cover a backend restart without leaving a player who mistyped a name waiting forever.
        return new BackendSwitchConfig(30_000, 3_000, 20_000, 5_000);
    }
}
