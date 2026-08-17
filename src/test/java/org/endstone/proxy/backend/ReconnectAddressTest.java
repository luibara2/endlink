package org.endstone.proxy.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReconnectAddressTest {

    /**
     * The case that actually broke: a Bedrock client's {@code ServerAddress} claim carries the port,
     * and handing the joined form back as a TransferPacket host makes the client refuse it with
     * "invalid IP address" and leave the proxy altogether.
     */
    @Test
    void splitsTheHostFromThePortAClientClaims() {
        ReconnectAddress address = ReconnectAddress.parse("play.example.com:19132", 19132);

        assertEquals("play.example.com", address.host());
        assertEquals(19132, address.port());
    }

    @Test
    void keepsTheClaimedPortOverTheListenersOwn() {
        // The player reached the proxy through a forwarded port; sending them back to the internal
        // one would fail for exactly the players who need it most.
        assertEquals(25565, ReconnectAddress.parse("play.example.com:25565", 19132).port());
    }

    @Test
    void fallsBackToTheListenerPortWhenNoneIsGiven() {
        ReconnectAddress address = ReconnectAddress.parse("play.example.com", 19132);

        assertEquals("play.example.com", address.host());
        assertEquals(19132, address.port());
    }

    /** A bare IPv6 literal is all colons and no port, and reaches a proxy on the same machine. */
    @Test
    void doesNotMistakeIpv6ForAHostAndPort() {
        ReconnectAddress address = ReconnectAddress.parse("::1", 19132);

        assertEquals("::1", address.host());
        assertEquals(19132, address.port());
    }

    @Test
    void readsBracketedIpv6WithAPort() {
        ReconnectAddress address = ReconnectAddress.parse("[::1]:19133", 19132);

        assertEquals("::1", address.host());
        assertEquals(19133, address.port());
    }

    @Test
    void readsBracketedIpv6WithoutAPort() {
        ReconnectAddress address = ReconnectAddress.parse("[fe80::1]", 19132);

        assertEquals("fe80::1", address.host());
        assertEquals(19132, address.port());
    }

    @Test
    void survivesANonsensePort() {
        assertEquals(19132, ReconnectAddress.parse("play.example.com:notaport", 19132).port());
        assertEquals(19132, ReconnectAddress.parse("play.example.com:0", 19132).port());
        assertEquals(19132, ReconnectAddress.parse("play.example.com:70000", 19132).port());
    }

    @Test
    void hasNothingToOfferWithoutAHost() {
        assertNull(ReconnectAddress.parse(null, 19132));
        assertNull(ReconnectAddress.parse("", 19132));
        assertNull(ReconnectAddress.parse("   ", 19132));
        assertNull(ReconnectAddress.parse(":19132", 19132));
        assertNull(ReconnectAddress.parse("[::1", 19132));
    }

    @Test
    void ignoresSurroundingWhitespace() {
        ReconnectAddress address = ReconnectAddress.parse("  play.example.com:19132  ", 1);

        assertEquals("play.example.com", address.host());
        assertEquals(19132, address.port());
    }
}
