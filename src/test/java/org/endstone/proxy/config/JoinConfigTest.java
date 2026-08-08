package org.endstone.proxy.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JoinConfigTest {
    private static final FailoverConfig FAILOVER =
            new FailoverConfig(true, List.of("lobby", "downtime"), Map.of());

    @Test
    void readsTheTryListInOrder() {
        Properties properties = new Properties();
        properties.setProperty("join.try", "default,lobby,downtime");

        JoinConfig join = JoinConfig.from(properties, FAILOVER);

        assertEquals(List.of("default", "lobby", "downtime"), join.tryOrder());
        assertEquals(1, join.attemptsPerBackend());
    }

    @Test
    void defaultsToTheFailoverChain() {
        // "Where does a player go when a backend is not available" is one question; an operator who
        // answered it for a backend dying should not have to answer it again for one that was
        // never up.
        JoinConfig join = JoinConfig.from(new Properties(), FAILOVER);

        assertEquals(List.of("lobby", "downtime"), join.tryOrder());
    }

    @Test
    void anExplicitlyEmptyTryListMeansTryOnlyWhereTheyWereRouted() {
        Properties properties = new Properties();
        properties.setProperty("join.try", "");

        assertEquals(List.of(), JoinConfig.from(properties, FAILOVER).tryOrder());
    }

    @Test
    void readsTheAttemptCount() {
        Properties properties = new Properties();
        properties.setProperty("join.attemptsPerBackend", "3");

        assertEquals(3, JoinConfig.from(properties, FAILOVER).attemptsPerBackend());
    }

    @Test
    void clampsAnAttemptCountBelowOne() {
        // Zero attempts would skip every candidate and disconnect everyone instantly.
        Properties properties = new Properties();
        properties.setProperty("join.attemptsPerBackend", "0");

        assertEquals(1, JoinConfig.from(properties, FAILOVER).attemptsPerBackend());
        assertThrows(IllegalArgumentException.class, () -> new JoinConfig(List.of(), 0));
    }

    @Test
    void stripsAnInlineComment() {
        Properties properties = new Properties();
        properties.setProperty("join.try", "default,lobby  # then give up");
        properties.setProperty("join.attemptsPerBackend", "2 # twice each");

        JoinConfig join = JoinConfig.from(properties, FAILOVER);

        assertEquals(List.of("default", "lobby"), join.tryOrder());
        assertEquals(2, join.attemptsPerBackend());
    }

    @Test
    void reachesTheProxyConfig() {
        Properties properties = new Properties();
        properties.setProperty("backends", "default,lobby");
        properties.setProperty("backend.default.host", "127.0.0.1");
        properties.setProperty("backend.lobby.host", "127.0.0.1");
        properties.setProperty("backend.lobby.port", "19134");
        properties.setProperty("join.try", "default,lobby");

        assertEquals(List.of("default", "lobby"), ProxyConfig.from(properties).join().tryOrder());
    }
}
