package org.endstone.proxy.backend;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReconnectRoutesTest {

    /** A clock the test moves by hand; the whole point of this class is what happens over time. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-08-17T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void deliversThePlayerWhereTheyWereSent() {
        ReconnectRoutes routes = new ReconnectRoutes(new TestClock(), 60_000L);
        routes.remember("2535459084817261", "javatest");

        assertEquals("javatest", routes.take("2535459084817261"));
    }

    /**
     * The route is consumed by the reconnect it belongs to. Left in place it would redirect the
     * player's next ordinary login as well, sending them somewhere they never asked for.
     */
    @Test
    void isSingleUse() {
        ReconnectRoutes routes = new ReconnectRoutes(new TestClock(), 60_000L);
        routes.remember("2535459084817261", "javatest");

        assertEquals("javatest", routes.take("2535459084817261"));
        assertNull(routes.take("2535459084817261"), "a consumed route must not fire twice");
    }

    /**
     * A player who closed the game instead of reconnecting must come back to their normal landing
     * spot, however much later that is.
     */
    @Test
    void expiresRatherThanRedirectingAMuchLaterLogin() {
        TestClock clock = new TestClock();
        ReconnectRoutes routes = new ReconnectRoutes(clock, 60_000L);
        routes.remember("2535459084817261", "javatest");

        clock.advance(Duration.ofMinutes(5));

        assertNull(routes.take("2535459084817261"));
        assertEquals(0, routes.size(), "an expired route must not be left behind");
    }

    @Test
    void ignoresPlayersItWasNeverToldAbout() {
        ReconnectRoutes routes = new ReconnectRoutes(new TestClock(), 60_000L);
        assertNull(routes.take("2535459084817261"));
        assertNull(routes.take(null));
    }

    /** A blank XUID would collide every anonymous player onto one route. */
    @Test
    void refusesToRememberWithoutAnIdentity() {
        ReconnectRoutes routes = new ReconnectRoutes(new TestClock(), 60_000L);
        routes.remember("", "javatest");
        routes.remember(null, "javatest");
        routes.remember("2535459084817261", null);

        assertEquals(0, routes.size());
    }

    @Test
    void aSecondDestinationReplacesTheFirst() {
        ReconnectRoutes routes = new ReconnectRoutes(new TestClock(), 60_000L);
        routes.remember("2535459084817261", "javatest");
        routes.remember("2535459084817261", "jakes");

        assertEquals("jakes", routes.take("2535459084817261"));
    }
}
