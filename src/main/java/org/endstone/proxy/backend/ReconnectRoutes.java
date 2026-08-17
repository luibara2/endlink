package org.endstone.proxy.backend;

import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a player should land on their <em>next</em> login, when the proxy has just asked them to
 * reconnect.
 *
 * <p>Some backends cannot be joined by a seamless handoff — see
 * {@link org.endstone.proxy.palette.BackendPalette#withBlockIdsHashed}. Those are reached by sending
 * the client back to the proxy's own address, which means the destination has to survive the gap
 * between the transfer and the new login. That is all this holds.</p>
 *
 * <p>Deliberately short-lived and single-use. A route that outlived its reconnect would silently
 * redirect an ordinary login much later — the player logs in tomorrow and lands somewhere they never
 * asked for, with nothing in the log to explain it. Expiry is a correctness property here, not
 * housekeeping.</p>
 */
public final class ReconnectRoutes {
    /**
     * Long enough for a client to tear down its session, reconnect and re-run the login handshake
     * including resource packs, short enough that a player who gave up and closed the game does not
     * find themselves redirected when they come back.
     */
    static final long DEFAULT_TTL_MILLIS = 60_000L;

    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long ttlMillis;

    public ReconnectRoutes() {
        this(Clock.systemUTC(), DEFAULT_TTL_MILLIS);
    }

    ReconnectRoutes(Clock clock, long ttlMillis) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.ttlMillis = ttlMillis < 1 ? DEFAULT_TTL_MILLIS : ttlMillis;
    }

    /** Keyed on XUID, which is the one identifier that survives a reconnect unchanged. */
    public void remember(String xuid, String backendName) {
        if (isBlank(xuid) || isBlank(backendName)) {
            return;
        }
        prune();
        routes.put(xuid, new Route(backendName, clock.millis() + ttlMillis));
    }

    /**
     * Takes the pending destination for this player, if any.
     *
     * <p>Consumed rather than read: the reconnect it belongs to has now happened. If that login
     * fails for some other reason the player falls back to the ordinary join path, which is the
     * right outcome — retrying a route the client could not use once is not obviously better.</p>
     */
    public String take(String xuid) {
        if (isBlank(xuid)) {
            return null;
        }
        Route route = routes.remove(xuid);
        if (route == null) {
            return null;
        }
        return route.expiresAtMillis() < clock.millis() ? null : route.backendName();
    }

    public void forget(String xuid) {
        if (!isBlank(xuid)) {
            routes.remove(xuid);
        }
    }

    public int size() {
        prune();
        return routes.size();
    }

    private void prune() {
        long now = clock.millis();
        Iterator<Route> iterator = routes.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().expiresAtMillis() < now) {
                iterator.remove();
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Route(String backendName, long expiresAtMillis) {
    }
}
