package org.endstone.proxy.security;

import org.endstone.proxy.config.SecurityConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Caps how many sessions one address may hold at once, and how fast it may open new ones.
 *
 * <p>RakNet's own limits are per-datagram and global: {@code RAK_MAX_CONNECTIONS} is a single pool
 * everyone draws from, so without this one host can hold every slot and nobody else gets in. Each
 * accepted session also costs the proxy a full backend dial-out, which makes the connection
 * <em>rate</em> matter as much as the count — an unthrottled attacker turns one UDP stream into a
 * flood of handshakes against every backend.</p>
 *
 * <p>Limits are per IP, not per {@code (ip, port)}: the source port changes on every reconnect, so
 * counting by socket address would count nothing. That does mean players behind one home NAT share
 * a budget, which is why {@link SecurityConfig#maxConnectionsPerAddress()} defaults well above 1.</p>
 *
 * <p>Called from Netty I/O threads, so the map is concurrent and no lock is held across a callback.</p>
 */
public final class ConnectionThrottle {
    /** Above this many tracked addresses, sweep the expired ones. Ordinary traffic never reaches it. */
    private static final int SWEEP_THRESHOLD = 4096;

    private final SecurityConfig config;
    private final LongSupplier clock;
    private final Map<InetAddress, AddressState> states = new ConcurrentHashMap<>();

    public ConnectionThrottle(SecurityConfig config) {
        this(config, System::currentTimeMillis);
    }

    public ConnectionThrottle(SecurityConfig config, LongSupplier clock) {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        this.config = config;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /**
     * Claims a session slot for an address.
     *
     * @return false if the address is over either limit, in which case the caller must close the
     *         session and must <em>not</em> call {@link #release}
     */
    public boolean accept(SocketAddress socketAddress) {
        InetAddress address = addressOf(socketAddress);
        if (address == null) {
            return true;
        }
        long now = clock.getAsLong();
        sweepIfCrowded(now);

        AddressState state = states.computeIfAbsent(address, key -> new AddressState());
        synchronized (state) {
            if (state.open >= config.maxConnectionsPerAddress()) {
                report(address, state, now, "already has %d open session(s)", state.open);
                return false;
            }
            if (now - state.windowStartedAtMillis >= config.connectionAttemptWindowMillis()) {
                state.windowStartedAtMillis = now;
                state.attempts = 0;
            }
            if (state.attempts >= config.maxConnectionAttempts()) {
                report(address, state, now, "opened %d session(s) within %dms",
                        state.attempts, config.connectionAttemptWindowMillis());
                return false;
            }
            state.attempts++;
            state.open++;
            state.lastSeenAtMillis = now;
            return true;
        }
    }

    /** Returns a slot claimed by a successful {@link #accept}. */
    public void release(SocketAddress socketAddress) {
        InetAddress address = addressOf(socketAddress);
        if (address == null) {
            return;
        }
        AddressState state = states.get(address);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.open > 0) {
                state.open--;
            }
            state.lastSeenAtMillis = clock.getAsLong();
        }
    }

    /** Open sessions currently attributed to an address. Exposed for tests and diagnostics. */
    public int openSessions(InetAddress address) {
        AddressState state = states.get(address);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.open;
        }
    }

    private static InetAddress addressOf(SocketAddress socketAddress) {
        return socketAddress instanceof InetSocketAddress inet ? inet.getAddress() : null;
    }

    /**
     * A refused address usually keeps trying, and one log line per attempt is how a throttle turns a
     * flood into a disk-space problem. One line per address per window is enough to see it happening.
     */
    private void report(InetAddress address, AddressState state, long now, String detail, Object... args) {
        state.lastSeenAtMillis = now;
        if (now - state.lastReportedAtMillis < config.connectionAttemptWindowMillis()) {
            return;
        }
        state.lastReportedAtMillis = now;
        System.out.printf(
                "Refused connection from %s: it %s.%n",
                address.getHostAddress(),
                String.format(detail, args)
        );
    }

    private void sweepIfCrowded(long now) {
        if (states.size() < SWEEP_THRESHOLD) {
            return;
        }
        long idleCutoff = now - Math.max(config.connectionAttemptWindowMillis(), 60_000L);
        Iterator<Map.Entry<InetAddress, AddressState>> entries = states.entrySet().iterator();
        while (entries.hasNext()) {
            AddressState state = entries.next().getValue();
            synchronized (state) {
                if (state.open == 0 && state.lastSeenAtMillis < idleCutoff) {
                    entries.remove();
                }
            }
        }
    }

    private static final class AddressState {
        private int open;
        private int attempts;
        private long windowStartedAtMillis;
        private long lastSeenAtMillis;
        private long lastReportedAtMillis = Long.MIN_VALUE / 2;
    }
}
