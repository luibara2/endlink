package org.endstone.proxy.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pending-join TTL has to outlast a client's resource pack download, not just the backend
 * handshake.
 *
 * <p>The proxy registers the pending join on NetworkSettings, at the start of the backend handshake,
 * but every verifier build — stock, modified and geyser alike — only calls back once the backend
 * reaches its login event, on the far side of the client's pack download. At the old 15s a player
 * pulling a few megabytes of packs was turned away with "Proxy verification failed."
 */
class PendingJoinTtlDefaultTest {
    private static final long SLOW_RESOURCE_PACK_DOWNLOAD_MILLIS = 120_000;

    @Test
    void defaultTtlOutlastsASlowResourcePackDownload() {
        ProxyConfig config = ProxyConfig.from(new Properties());

        long ttl = config.backendVerification().pendingJoinTtlMillis();
        assertEquals(600_000, ttl);
        assertTrue(
                ttl > SLOW_RESOURCE_PACK_DOWNLOAD_MILLIS,
                "the default TTL must cover a slow pack download, was " + ttl + "ms"
        );
    }
}
