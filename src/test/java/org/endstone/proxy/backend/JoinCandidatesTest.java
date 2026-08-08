package org.endstone.proxy.backend;

import org.endstone.proxy.config.BackendConfig;
import org.endstone.proxy.config.JoinConfig;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a joining player gets when their backend will not have them. Before this existed they were
 * simply kicked — mid-session failover only rescues someone already in a world.
 */
final class JoinCandidatesTest {
    @Test
    void triesWhereTheyWereRoutedFirstThenTheList() {
        List<String> names = expand("default", new JoinConfig(List.of("lobby", "downtime"), 1));

        assertEquals(List.of("default", "lobby", "downtime"), names);
    }

    @Test
    void keepsTheRoutedBackendFirstEvenWhenTheListNamesItLater() {
        // A forced host sent them to lobby; the global try-list still starts with default. They
        // should get the backend they asked for before anything else.
        List<String> names = expand("lobby", new JoinConfig(List.of("default", "lobby"), 1));

        assertEquals(List.of("lobby", "default"), names);
    }

    @Test
    void neverGivesOneBackendTwoTurns() {
        List<String> names = expand("default", new JoinConfig(List.of("default", "lobby", "lobby"), 1));

        assertEquals(List.of("default", "lobby"), names);
    }

    @Test
    void repeatsEachCandidateForTheConfiguredAttemptCount() {
        // A backend that is down fails fast; one that is restarting may refuse a connection a second
        // before it would have accepted it.
        List<String> names = expand("default", new JoinConfig(List.of("lobby"), 2));

        assertEquals(List.of("default", "default", "lobby", "lobby"), names);
    }

    @Test
    void skipsTryListNamesThatAreNotConfiguredBackends() {
        List<String> names = expand("default", new JoinConfig(List.of("lbby", "lobby"), 1));

        assertEquals(List.of("default", "lobby"), names);
    }

    @Test
    void anEmptyTryListLeavesExactlyTheRoutedBackend() {
        // The pre-existing behaviour: one attempt, then the player is told the network is down.
        assertEquals(List.of("default"), expand("default", new JoinConfig(List.of(), 1)));
    }

    @Test
    void aRestrictedBackendIsStillAValidJoinTarget() {
        // Same rule as failover: the operator wrote the try-list, so a downtime server that no
        // player may reach with /server is exactly where they should land when nothing else is up.
        List<String> names = expand("default", new JoinConfig(List.of("downtime"), 1));

        assertEquals(List.of("default", "downtime"), names);
    }

    private static List<String> expand(String routed, JoinConfig join) {
        BackendDirectory directory = directory();
        return JoinCandidates.expand(directory.find(routed).orElseThrow(), join, directory)
                .stream()
                .map(BackendConfig::name)
                .toList();
    }

    private static BackendDirectory directory() {
        Map<String, BackendConfig> backends = new LinkedHashMap<>();
        backends.put("default", backend("default", 19133));
        backends.put("lobby", backend("lobby", 19134));
        backends.put("downtime", backend("downtime", 19136));
        return new BackendDirectory(backends, "default", "lobby");
    }

    private static BackendConfig backend(String name, int port) {
        return new BackendConfig(name, InetSocketAddress.createUnresolved("127.0.0.1", port));
    }
}
