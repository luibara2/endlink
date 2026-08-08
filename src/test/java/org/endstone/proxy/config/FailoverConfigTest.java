package org.endstone.proxy.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FailoverConfigTest {
    @Test
    void usesTheGlobalListForBackendsWithoutAnOverride() {
        FailoverConfig config = new FailoverConfig(true, List.of("lobby", "hub"), Map.of());

        assertEquals(List.of("lobby", "hub"), config.fallbacksFor("survival"));
    }

    @Test
    void perBackendOverrideWinsOverTheGlobalList() {
        FailoverConfig config = new FailoverConfig(
                true,
                List.of("lobby"),
                Map.of("survival", List.of("hub", "creative"))
        );

        assertEquals(List.of("hub", "creative"), config.fallbacksFor("survival"));
        assertEquals(List.of("lobby"), config.fallbacksFor("creative"));
    }

    @Test
    void anExplicitlyEmptyOverrideDisablesFailoverForThatBackendOnly() {
        FailoverConfig config = new FailoverConfig(
                true,
                List.of("lobby"),
                Map.of("arena", List.of())
        );

        assertTrue(config.fallbacksFor("arena").isEmpty());
        assertEquals(List.of("lobby"), config.fallbacksFor("survival"));
    }

    @Test
    void neverSendsAPlayerBackToTheBackendTheyJustLost() {
        FailoverConfig config = new FailoverConfig(true, List.of("lobby", "hub"), Map.of());

        assertEquals(List.of("hub"), config.fallbacksFor("lobby"));
    }

    @Test
    void resolvesNamesCaseInsensitivelyAndDropsDuplicates() {
        FailoverConfig config = new FailoverConfig(
                true,
                List.of(" Lobby ", "lobby", "HUB"),
                Map.of("SURVIVAL", List.of("Hub"))
        );

        assertEquals(List.of("lobby", "hub"), config.fallbacksFor("creative"));
        assertEquals(List.of("hub"), config.fallbacksFor("survival"));
    }

    @Test
    void disablingFailoverEmptiesEveryChain() {
        FailoverConfig config = new FailoverConfig(
                false,
                List.of("lobby"),
                Map.of("survival", List.of("hub"))
        );

        assertTrue(config.fallbacksFor("survival").isEmpty());
        assertTrue(config.fallbacksFor("creative").isEmpty());
    }

    @Test
    void toleratesAnUnknownBackendName() {
        FailoverConfig config = new FailoverConfig(true, List.of("lobby"), Map.of());

        assertEquals(List.of("lobby"), config.fallbacksFor(null));
    }

    @Test
    void defaultsToTheHubBackendWhenNothingIsConfigured() {
        Properties properties = new Properties();
        properties.setProperty("backends", "default,hub");
        properties.setProperty("backend.hub.host", "10.0.0.6");
        properties.setProperty("hubBackend", "hub");

        FailoverConfig config = ProxyConfig.from(properties).failover();

        assertTrue(config.enabled());
        assertEquals(List.of("hub"), config.fallbacksFor("default"));
    }

    @Test
    void readsGlobalAndPerBackendListsFromProperties() {
        Properties properties = new Properties();
        properties.setProperty("backends", "default,hub,survival,arena");
        properties.setProperty("backend.hub.host", "10.0.0.6");
        properties.setProperty("backend.survival.host", "10.0.0.7");
        properties.setProperty("backend.arena.host", "10.0.0.8");
        properties.setProperty("failover.fallbacks", "hub, default");
        properties.setProperty("backend.survival.fallback", "arena,hub");
        properties.setProperty("backend.arena.fallback", "");

        FailoverConfig config = ProxyConfig.from(properties).failover();

        assertEquals(List.of("hub", "default"), config.fallbacksFor("default_unknown"));
        assertEquals(List.of("arena", "hub"), config.fallbacksFor("survival"));
        assertTrue(config.fallbacksFor("arena").isEmpty());
    }

    @Test
    void ignoresATrailingCommentThatPropertiesWouldKeepAsPartOfTheValue() {
        // Properties only honours '#' at the start of a line, so this arrives as one long backend
        // name. Left alone it matches nothing and the player is kicked instead of moved.
        Properties properties = new Properties();
        properties.setProperty("backends", "default,lobby");
        properties.setProperty("backend.lobby.host", "127.0.0.1");
        properties.setProperty("failover.fallbacks", "lobby      # ordered global try-list");
        properties.setProperty("backend.default.fallback", "lobby    # per-backend override");

        FailoverConfig config = ProxyConfig.from(properties).failover();

        assertEquals(List.of("lobby"), config.fallbacksFor("default"));
        assertEquals(List.of("lobby"), config.fallbacksFor("survival"));
    }

    @Test
    void stillTreatsAnEmptyValueAsDisablingFailoverEvenWithAComment() {
        Properties properties = new Properties();
        properties.setProperty("backends", "default,lobby");
        properties.setProperty("backend.lobby.host", "127.0.0.1");
        properties.setProperty("backend.default.fallback", "# nowhere to go from here");

        assertTrue(ProxyConfig.from(properties).failover().fallbacksFor("default").isEmpty());
    }

    @Test
    void honoursTheDisableSwitch() {
        Properties properties = new Properties();
        properties.setProperty("failover.enabled", "false");

        assertTrue(ProxyConfig.from(properties).failover().fallbacksFor("default").isEmpty());
    }
}
