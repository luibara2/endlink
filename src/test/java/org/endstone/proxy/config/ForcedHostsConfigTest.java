package org.endstone.proxy.config;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hostname is whatever the client put in its {@code ServerAddress} claim, so the parsing has to
 * cope with everything a real client sends — a port, a trailing root dot, mixed case, an IPv6
 * literal — and with nothing at all.
 */
class ForcedHostsConfigTest {
    private static final ForcedHostsConfig HOSTS = new ForcedHostsConfig(Map.of(
            "play.example.com", "survival",
            "creative.example.com", "creative"
    ));

    @Test
    void routesAKnownHostname() {
        assertEquals("survival", HOSTS.backendFor("play.example.com").orElseThrow());
        assertEquals("creative", HOSTS.backendFor("creative.example.com").orElseThrow());
    }

    @Test
    void ignoresThePortTheClientConnectedOn() {
        assertEquals("survival", HOSTS.backendFor("play.example.com:19132").orElseThrow());
    }

    @Test
    void ignoresCaseAndATrailingRootDot() {
        assertEquals("survival", HOSTS.backendFor("PLAY.Example.COM.").orElseThrow());
        assertEquals("survival", HOSTS.backendFor("  play.example.com  ").orElseThrow());
    }

    @Test
    void anUnknownHostnameIsNotAnError() {
        // A DNS name pointed at the proxy before its config entry exists must still let people in,
        // on the default backend.
        assertTrue(HOSTS.backendFor("other.example.com").isEmpty());
        assertTrue(HOSTS.backendFor("").isEmpty());
        assertTrue(HOSTS.backendFor(null).isEmpty());
    }

    @Test
    void keepsAnIpv6LiteralIntact() {
        ForcedHostsConfig hosts = new ForcedHostsConfig(Map.of("[::1]", "lobby", "::1", "lobby"));

        assertEquals("lobby", hosts.backendFor("[::1]:19132").orElseThrow());
        assertEquals("lobby", hosts.backendFor("::1").orElseThrow());
    }

    @Test
    void readsOneLinePerHostnameFromProperties() {
        Properties properties = new Properties();
        properties.setProperty("forcedHost.play.example.com", "survival");
        properties.setProperty("forcedHost.creative.example.com", "creative ");
        properties.setProperty("backend.default.host", "127.0.0.1");
        properties.setProperty("forcedHost.", "ignored");

        ForcedHostsConfig hosts = ForcedHostsConfig.from(properties);

        assertEquals(2, hosts.byHostname().size());
        assertEquals("survival", hosts.backendFor("play.example.com").orElseThrow());
        assertEquals("creative", hosts.backendFor("creative.example.com").orElseThrow());
    }

    @Test
    void dropsAForcedHostPointingAtAnUnknownBackend() {
        Properties properties = new Properties();
        properties.setProperty("backends", "lobby");
        properties.setProperty("backend.lobby.host", "127.0.0.1");
        properties.setProperty("backend.lobby.port", "19134");
        properties.setProperty("backend.name", "lobby");
        properties.setProperty("hubBackend", "lobby");
        properties.setProperty("forcedHost.play.example.com", "survival");
        properties.setProperty("forcedHost.hub.example.com", "lobby");

        ProxyConfig config = ProxyConfig.from(properties);

        // Otherwise the mistake only shows up as players landing on the default backend for no
        // visible reason.
        assertTrue(config.forcedHosts().backendFor("play.example.com").isEmpty());
        assertEquals("lobby", config.forcedHosts().backendFor("hub.example.com").orElseThrow());
    }

    @Test
    void stripsAnInlineCommentFromTheBackendName() {
        Properties properties = new Properties();
        properties.setProperty("forcedHost.play.example.com", "survival  # the main server");

        ForcedHostsConfig hosts = ForcedHostsConfig.from(properties);

        assertEquals("survival", hosts.backendFor("play.example.com").orElseThrow());
    }
}
