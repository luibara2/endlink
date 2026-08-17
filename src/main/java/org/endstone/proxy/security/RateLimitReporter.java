package org.endstone.proxy.security;

import org.cloudburstmc.netty.channel.raknet.config.RakServerMetrics;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Explains a RakNet packet-limit block in terms an operator can act on.
 *
 * <p>The limiter's own line — {@code Blocked because packet limit was reached} — names neither the
 * setting that caused it nor the fact that the address stays blocked for ten seconds, during which a
 * joining player's handshake stalls and ends as {@code disconnect.timeout}. The two together look
 * like an unrelated network fault, and the player simply cannot join: the client retries, trips the
 * same limit, and loops.</p>
 *
 * <p>The limit is per address per 10ms tick, so what trips it is a burst, not sustained traffic. Two
 * legitimate ones do: a Bedrock login is a run of MTU-sized fragments carrying the player's chain and
 * skin, and a resource-pack download is answered by a stream of acknowledgements. Both arrive far
 * faster over loopback or a LAN than over the internet the default was chosen for.</p>
 */
public final class RateLimitReporter implements RakServerMetrics {
    private final int packetLimit;
    private final Map<InetAddress, Long> lastReported = new ConcurrentHashMap<>();
    /** One explanation per address per minute: a blocked client retries, and each retry re-blocks. */
    private static final long REPEAT_MILLIS = 60_000;

    public RateLimitReporter(int packetLimit) {
        this.packetLimit = packetLimit;
    }

    @Override
    public void addressBlocked(InetAddress address) {
        long now = System.currentTimeMillis();
        Long previous = lastReported.get(address);
        if (previous != null && now - previous < REPEAT_MILLIS) {
            return;
        }
        lastReported.put(address, now);
        System.out.printf(
                "%s sent more than %d datagrams in one 10ms tick and is blocked for 10 seconds "
                        + "(security.rateLimit.packetLimit). A login burst or a resource-pack download can do "
                        + "this on a fast link; if real players cannot join, raise the limit rather than "
                        + "turning the limiter off.%n",
                address.getHostAddress(), packetLimit
        );
    }
}
