package org.endstone.proxy.backend;

import org.endstone.proxy.config.BackendConfig;
import org.endstone.proxy.config.FailoverConfig;
import org.endstone.proxy.config.PermissionsConfig;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BackendFailoverTest {
    @Test
    void resolvesTheConfiguredChainInOrder() {
        FailoverConfig config = new FailoverConfig(true, List.of("hub", "lobby"), Map.of());

        assertEquals(
                List.of("hub", "lobby"),
                names(BackendFailover.targets(config, directory(), "survival"))
        );
    }

    @Test
    void skipsFallbackNamesThatAreNotConfiguredBackends() {
        // A typo in the config must cost the player at most one candidate, never their session.
        FailoverConfig config = new FailoverConfig(true, List.of("hbu", "hub"), Map.of());

        assertEquals(List.of("hub"), names(BackendFailover.targets(config, directory(), "survival")));
    }

    @Test
    void excludesTheBackendThatJustDied() {
        FailoverConfig config = new FailoverConfig(true, List.of("hub", "lobby"), Map.of());

        assertEquals(List.of("lobby"), names(BackendFailover.targets(config, directory(), "hub")));
    }

    @Test
    void appliesThePerBackendOverride() {
        FailoverConfig config = new FailoverConfig(
                true,
                List.of("hub"),
                Map.of("survival", List.of("lobby", "hub"))
        );

        assertEquals(
                List.of("lobby", "hub"),
                names(BackendFailover.targets(config, directory(), "survival"))
        );
    }

    @Test
    void yieldsNoTargetsWhenFailoverIsDisabled() {
        FailoverConfig config = new FailoverConfig(false, List.of("hub"), Map.of());

        assertTrue(BackendFailover.targets(config, directory(), "survival").isEmpty());
    }

    @Test
    void yieldsNoTargetsWhenTheOnlyCandidateIsTheDeadBackend() {
        FailoverConfig config = new FailoverConfig(true, List.of("hub"), Map.of());

        assertTrue(BackendFailover.targets(config, directory(), "hub").isEmpty());
    }

    /**
     * The maintenance-server arrangement: a backend marked {@code adminOnly} so no player can reach
     * it with {@code /server}, configured as the failover target so everyone lands there when a real
     * backend dies.
     *
     * <p>Failover resolves purely from the configured chain and never consults permissions — the
     * operator wrote that chain, and the alternative is disconnecting a player for lacking access to
     * the only place left to put them. This test exists because that bypass looks like an oversight
     * until you see what it is for.</p>
     */
    @Test
    void aRestrictedBackendIsStillAValidFailoverTarget() {
        Map<String, BackendConfig> backends = new LinkedHashMap<>(Map.of(
                "hub", backend("hub", 19133),
                "survival", backend("survival", 19135),
                "downtime", backend("downtime", 19136)
        ));
        BackendDirectory directory = new BackendDirectory(backends, "survival", "hub");
        PermissionsConfig permissions = new PermissionsConfig(Set.of(), Set.of(), Set.of("downtime"));
        FailoverConfig failover = new FailoverConfig(true, List.of("downtime"), Map.of());

        // Hidden from self-service...
        assertFalse(permissions.mayJoinBackend("2535412345678901", "Steve", "downtime"));
        // ...and still where a player goes when their backend dies under them.
        assertEquals(List.of("downtime"), names(BackendFailover.targets(failover, directory, "survival")));
    }

    private static List<String> names(List<BackendConfig> backends) {
        return backends.stream().map(BackendConfig::name).toList();
    }

    private static BackendDirectory directory() {
        Map<String, BackendConfig> backends = new LinkedHashMap<>();
        backends.put("hub", backend("hub", 19133));
        backends.put("lobby", backend("lobby", 19134));
        backends.put("survival", backend("survival", 19135));
        return new BackendDirectory(backends, "survival", "hub");
    }

    private static BackendConfig backend(String name, int port) {
        return new BackendConfig(name, InetSocketAddress.createUnresolved("127.0.0.1", port));
    }
}
