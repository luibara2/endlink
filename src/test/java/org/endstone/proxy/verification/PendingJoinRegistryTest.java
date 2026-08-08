package org.endstone.proxy.verification;

import org.endstone.proxy.auth.AuthData;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PendingJoinRegistryTest {
    @Test
    void consumesMatchingPendingJoinOnce() {
        UUID uuid = UUID.randomUUID();
        PendingJoinRegistry registry = new PendingJoinRegistry(fixedClock(), 15_000);
        registry.register(new AuthData("Steve", uuid, "123"), "default");

        VerificationRequest request = new VerificationRequest("123", uuid.toString(), "Steve", "127.0.0.1", 1_000, "n");

        assertEquals(PendingJoinRegistry.ConsumeResult.ACCEPTED, registry.consume(request));
        assertEquals(PendingJoinRegistry.ConsumeResult.NOT_FOUND, registry.consume(request));
    }

    @Test
    void returnsNotFoundForUnknownName() {
        UUID uuid = UUID.randomUUID();
        PendingJoinRegistry registry = new PendingJoinRegistry(fixedClock(), 15_000);
        registry.register(new AuthData("Steve", uuid, "123"), "default");

        VerificationRequest request = new VerificationRequest("123", uuid.toString(), "Alex", "127.0.0.1", 1_000, "n");

        assertEquals(PendingJoinRegistry.ConsumeResult.NOT_FOUND, registry.consume(request));
    }

    @Test
    void acceptsWhenRequestUuidIsBlank() {
        UUID uuid = UUID.randomUUID();
        PendingJoinRegistry registry = new PendingJoinRegistry(fixedClock(), 15_000);
        registry.register(new AuthData("Steve", uuid, "123"), "default");

        VerificationRequest request = new VerificationRequest("", "", "Steve", "127.0.0.1", 1_000, "n");

        assertEquals(PendingJoinRegistry.ConsumeResult.ACCEPTED, registry.consume(request));
    }

    @Test
    void acceptsWhenRequestUuidDiffersFromRegistered() {
        UUID uuid = UUID.randomUUID();
        PendingJoinRegistry registry = new PendingJoinRegistry(fixedClock(), 15_000);
        registry.register(new AuthData("Steve", uuid, "123"), "default");

        // BDS-side player.unique_id is a session-derived UUID we cannot predict; the lookup
        // matches by name only and the HMAC signature is what enforces trust.
        VerificationRequest request = new VerificationRequest("", UUID.randomUUID().toString(), "Steve", "127.0.0.1", 1_000, "n");

        assertEquals(PendingJoinRegistry.ConsumeResult.ACCEPTED, registry.consume(request));
    }

    @Test
    void preservesRealClientEndpointForBackendPlugins() {
        UUID uuid = UUID.randomUUID();
        PendingJoinRegistry registry = new PendingJoinRegistry(fixedClock(), 15_000);

        PendingJoin pendingJoin = registry.register(
                new AuthData("Steve", uuid, "123"),
                "default",
                new InetSocketAddress("203.0.113.42", 42123)
        );

        assertEquals("203.0.113.42", pendingJoin.clientIp());
        assertEquals(42123, pendingJoin.clientPort());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);
    }
}
