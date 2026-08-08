package org.endstone.proxy.backend;

import org.endstone.proxy.config.BackendConfig;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BackendDirectoryTransferTest {

    @Test
    void findsConfiguredTransferTargetByHostnameAndPort() {
        BackendDirectory directory = directory(
                backend("lobby", "lobby.internal", 19132),
                backend("survival", "survival.internal", 19133)
        );

        assertEquals("survival", directory.findByAddress("SURVIVAL.INTERNAL.", 19133)
                .orElseThrow()
                .name());
    }

    @Test
    void doesNotTreatSameHostOnAnotherPortAsInternal() {
        BackendDirectory directory = directory(backend("lobby", "lobby.internal", 19132));

        assertTrue(directory.findByAddress("lobby.internal", 19133).isEmpty());
    }

    @Test
    void leavesExternalTransfersUnmatched() {
        BackendDirectory directory = directory(backend("lobby", "lobby.internal", 19132));

        assertTrue(directory.findByAddress("play.external.example", 19132).isEmpty());
    }

    @Test
    void acceptsNumericAddressOfAResolvedConfiguredHost() {
        BackendConfig lobby = new BackendConfig("lobby", new InetSocketAddress("127.0.0.1", 19132));
        BackendDirectory directory = directory(lobby);

        assertEquals("lobby", directory.findByAddress("127.0.0.1", 19132)
                .orElseThrow()
                .name());
    }

    private static BackendDirectory directory(BackendConfig... configs) {
        Map<String, BackendConfig> backends = new LinkedHashMap<>();
        for (BackendConfig config : configs) {
            backends.put(config.name(), config);
        }
        return new BackendDirectory(backends, configs[0].name(), configs[0].name());
    }

    private static BackendConfig backend(String name, String host, int port) {
        return new BackendConfig(name, InetSocketAddress.createUnresolved(host, port));
    }
}
