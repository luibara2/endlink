package org.endstone.proxy.verification;

import org.endstone.proxy.auth.AuthData;

import java.net.SocketAddress;
import java.time.Clock;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class PendingJoinRegistry {
    public enum ConsumeResult {
        ACCEPTED,
        NOT_FOUND,
        EXPIRED,
        IDENTITY_MISMATCH
    }

    public record ConsumedJoin(ConsumeResult result, PendingJoin pendingJoin) {
    }

    private final ConcurrentHashMap<String, PendingJoin> pendingJoins = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long ttlMillis;

    public PendingJoinRegistry(Clock clock, long ttlMillis) {
        if (clock == null) {
            throw new IllegalArgumentException("clock cannot be null");
        }
        if (ttlMillis < 1) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        this.clock = clock;
        this.ttlMillis = ttlMillis;
    }

    public PendingJoin register(AuthData authData, String backendName) {
        return register(authData, backendName, null);
    }

    public PendingJoin register(AuthData authData, String backendName, SocketAddress clientAddress) {
        pruneExpired();
        PendingJoin pendingJoin = PendingJoin.from(
                authData,
                backendName,
                clientAddress,
                clock.millis() + ttlMillis
        );
        // Key by display name. BDS 1.26.10+ in offline mode does not trust the self-signed
        // OIDC `xid` and may not surface `identity` as player.unique_id either; the player
        // name (xname) is the one identifier that reliably propagates. The HMAC-signed
        // verification request is what enforces trust — name is just the lookup key.
        pendingJoins.put(key(authData.displayName()), pendingJoin);
        return pendingJoin;
    }

    public ConsumeResult consume(VerificationRequest request) {
        return consumeJoin(request).result();
    }

    public ConsumedJoin consumeJoin(VerificationRequest request) {
        PendingJoin pendingJoin = pendingJoins.remove(key(request.name()));
        if (pendingJoin == null) {
            return new ConsumedJoin(ConsumeResult.NOT_FOUND, null);
        }
        if (pendingJoin.expiresAtMillis() < clock.millis()) {
            return new ConsumedJoin(ConsumeResult.EXPIRED, pendingJoin);
        }
        // No UUID cross-check: BDS 1.26.10+ in offline mode generates a session-derived
        // unique_id that the proxy cannot predict (it isn't `nameUUIDFromBytes("pocket-auth-
        // 1-xuid:<xuid>")` or any other algorithm we can match). The HMAC-signed request
        // proves the plugin is legitimate; the short pending-join TTL prevents replay; and
        // Xbox Live enforces display-name uniqueness at any given instant.
        return new ConsumedJoin(ConsumeResult.ACCEPTED, pendingJoin);
    }

    public boolean removeIfPending(PendingJoin pendingJoin) {
        if (pendingJoin == null) {
            return false;
        }
        return pendingJoins.remove(key(pendingJoin.name()), pendingJoin);
    }

    public int size() {
        pruneExpired();
        return pendingJoins.size();
    }

    private void pruneExpired() {
        long now = clock.millis();
        Iterator<PendingJoin> iterator = pendingJoins.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().expiresAtMillis() < now) {
                iterator.remove();
            }
        }
    }

    private static String key(String xuid) {
        return xuid.toLowerCase(Locale.ROOT);
    }
}
