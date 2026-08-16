package org.endstone.proxy.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandsConfigTest {
    @Test
    void keepsEveryCommandForTheProxyByDefault() {
        CommandsConfig config = CommandsConfig.from(new Properties(), List.of("hub", "skygen"));

        assertTrue(config.isEmpty());
        assertEquals(Set.of(), config.passthroughFor("hub"));
        assertFalse(config.isPassthrough("hub", "server"));
        assertEquals(CommandsConfig.DEFAULT_QUALIFIER, config.qualifier());
    }

    /** The whole point: the same name means different things on different backends. */
    @Test
    void readsPassthroughPerBackend() {
        Properties properties = new Properties();
        properties.setProperty("backend.hub.passthroughCommands", "hub, server");

        CommandsConfig config = CommandsConfig.from(properties, List.of("hub", "skygen"));

        assertEquals(Set.of("hub", "server"), config.passthroughFor("hub"));
        assertTrue(config.isPassthrough("hub", "server"));
        assertFalse(config.isPassthrough("skygen", "server"));
        assertFalse(config.isPassthrough("hub", "lobby"));
    }

    @Test
    void matchesBackendAndCommandNamesCaseInsensitively() {
        Properties properties = new Properties();
        properties.setProperty("backend.hub.passthroughCommands", "HUB,Server");

        CommandsConfig config = CommandsConfig.from(properties, List.of("hub"));

        assertTrue(config.isPassthrough("HUB", "SERVER"));
    }

    /**
     * An explicit per-backend value replaces the global list rather than adding to it, so an empty
     * one is the way to exempt a single backend from a global default.
     */
    @Test
    void anExplicitlyEmptyBackendListOverridesTheGlobalDefault() {
        Properties properties = new Properties();
        properties.setProperty("commands.passthrough", "hub,server");
        properties.setProperty("backend.skygen.passthroughCommands", "");

        CommandsConfig config = CommandsConfig.from(properties, List.of("hub", "skygen"));

        assertEquals(Set.of("hub", "server"), config.passthroughFor("hub"));
        assertEquals(Set.of(), config.passthroughFor("skygen"));
    }

    @Test
    void unknownBackendsFallBackToTheGlobalList() {
        Properties properties = new Properties();
        properties.setProperty("commands.passthrough", "hub");

        CommandsConfig config = CommandsConfig.from(properties, List.of("hub"));

        assertEquals(Set.of("hub"), config.passthroughFor("a-backend-that-does-not-exist"));
    }

    /** Properties only treats '#' as a comment at the start of a line; see ConfigValues. */
    @Test
    void stripsInlineComments() {
        Properties properties = new Properties();
        properties.setProperty("backend.hub.passthroughCommands", "hub,server  # the hub plugin's");
        properties.setProperty("commands.qualifier", "px_  # colon-free");

        CommandsConfig config = CommandsConfig.from(properties, List.of("hub"));

        assertEquals(Set.of("hub", "server"), config.passthroughFor("hub"));
        assertEquals("px_", config.qualifier());
    }

    @Test
    void anEmptyQualifierIsCarriedThroughRatherThanDefaulted() {
        Properties properties = new Properties();
        properties.setProperty("commands.qualifier", "");

        assertEquals("", CommandsConfig.from(properties, List.of("hub")).qualifier());
    }

    @Test
    void reachesTheConfigThroughTheProxyPolicy() {
        Properties properties = new Properties();
        properties.setProperty("backends", "hub");
        properties.setProperty("backend.hub.host", "127.0.0.1");
        properties.setProperty("backend.hub.port", "19141");
        properties.setProperty("backend.hub.passthroughCommands", "hub,server");

        ProxyConfig config = ProxyConfig.from(properties);

        assertTrue(config.commands().isPassthrough("hub", "hub"));
        assertFalse(config.commands().isPassthrough("default", "hub"));
    }
}
