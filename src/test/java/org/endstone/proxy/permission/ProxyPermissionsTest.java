package org.endstone.proxy.permission;

import org.endstone.proxy.config.PermissionsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runtime half of the proxy's only authorisation boundary. The cases that matter are the ones
 * where someone could end up with access they were not given, or lose access they were.
 */
class ProxyPermissionsTest {
    private static final PermissionsConfig CONFIG = new PermissionsConfig(
            Set.of("configowner"),
            Set.of("send", "alert", "glist", "perm"),
            Set.of("staff")
    );

    private static ProxyPermissions permissions() {
        return ProxyPermissions.inMemory(CONFIG);
    }

    @Test
    void aGrantedCommandNodeOpensExactlyThatCommand() {
        ProxyPermissions permissions = permissions();

        permissions.grant("2535412345678901", "command.send");

        assertTrue(permissions.allows("2535412345678901", "Steve", "send"));
        // and nothing else
        assertFalse(permissions.allows("2535412345678901", "Steve", "alert"));
        assertFalse(permissions.isAdmin("2535412345678901", "Steve"));
    }

    @Test
    void theAdminNodeAnswersForEveryOtherNode() {
        ProxyPermissions permissions = permissions();

        permissions.grant("Steve", ProxyPermissions.ADMIN);

        assertTrue(permissions.isAdmin("999", "Steve"));
        assertTrue(permissions.allows("999", "Steve", "send"));
        assertTrue(permissions.allows("999", "Steve", "perm"));
        assertTrue(permissions.mayJoinBackend("999", "Steve", "staff"));
    }

    @Test
    void matchesEitherTheXuidOrTheGamertag() {
        ProxyPermissions permissions = permissions();

        permissions.grant("2535412345678901", "command.alert");

        assertTrue(permissions.allows("2535412345678901", "AnyName", "alert"));
        assertFalse(permissions.allows("2535499999999999", "AnyName", "alert"));
    }

    @Test
    void aRestrictedBackendNeedsItsOwnNode() {
        ProxyPermissions permissions = permissions();

        assertFalse(permissions.mayJoinBackend("999", "Steve", "staff"));
        // An unrestricted backend is open regardless.
        assertTrue(permissions.mayJoinBackend("999", "Steve", "lobby"));

        permissions.grant("Steve", "server.staff");

        assertTrue(permissions.mayJoinBackend("999", "Steve", "staff"));
    }

    @Test
    void theConfigIsAFloorThatRuntimeChangesCannotRemove() {
        ProxyPermissions permissions = permissions();
        assertTrue(permissions.isAdmin("999", "ConfigOwner"));

        // Nothing was granted at runtime, so there is nothing to revoke — and the config entry
        // survives regardless. That is the escape hatch when a grant goes wrong.
        assertFalse(permissions.revoke("configowner", ProxyPermissions.ADMIN));

        assertTrue(permissions.isAdmin("999", "ConfigOwner"));
    }

    @Test
    void revokingRemovesOnlyTheNamedNode() {
        ProxyPermissions permissions = permissions();
        permissions.grant("Steve", "command.send");
        permissions.grant("Steve", "command.glist");

        assertTrue(permissions.revoke("Steve", "command.send"));

        assertFalse(permissions.allows("999", "Steve", "send"));
        assertTrue(permissions.allows("999", "Steve", "glist"));
    }

    @Test
    void reportsWhetherAChangeActuallyHappened() {
        ProxyPermissions permissions = permissions();

        assertTrue(permissions.grant("Steve", "command.send"));
        assertFalse(permissions.grant("Steve", "command.send"));
        assertTrue(permissions.revoke("Steve", "command.send"));
        assertFalse(permissions.revoke("Steve", "command.send"));
    }

    @Test
    void refusesSubjectsAndNodesThatWouldNotSurviveTheFile() {
        ProxyPermissions permissions = permissions();

        // '=' and ':' are the properties-file separators, and ',' separates nodes — any of them
        // would read back as a different permission than the one granted.
        assertThrows(IllegalArgumentException.class, () -> permissions.grant("a=b", "admin"));
        assertThrows(IllegalArgumentException.class, () -> permissions.grant("a:b", "admin"));
        assertThrows(IllegalArgumentException.class, () -> permissions.grant("Steve", "a,b"));
        assertThrows(IllegalArgumentException.class, () -> permissions.grant("", "admin"));
    }

    @Test
    void survivesARestart(@TempDir Path directory) {
        Path file = directory.resolve("permissions.properties");

        ProxyPermissions first = ProxyPermissions.load(CONFIG, file);
        first.grant("2535412345678901", "command.send");
        first.grant("2535412345678901", "server.staff");
        first.grant("Steve", ProxyPermissions.ADMIN);

        assertTrue(Files.exists(file), "a grant that is not written down is gone on the next restart");

        ProxyPermissions reloaded = ProxyPermissions.load(CONFIG, file);

        assertTrue(reloaded.allows("2535412345678901", "Someone", "send"));
        assertTrue(reloaded.mayJoinBackend("2535412345678901", "Someone", "staff"));
        assertTrue(reloaded.isAdmin("999", "Steve"));
        assertFalse(reloaded.allows("999", "Nobody", "send"));
    }

    @Test
    void aMissingFileIsAnEmptyStoreRatherThanAFailure(@TempDir Path directory) {
        ProxyPermissions permissions = ProxyPermissions.load(CONFIG, directory.resolve("absent.properties"));

        assertTrue(permissions.subjects().isEmpty());
        // The config still applies, so the proxy is administrable on a fresh install.
        assertTrue(permissions.isAdmin("999", "configowner"));
    }

    @Test
    void listsTheNodesWorthGranting() {
        List<String> nodes = ProxyPermissions.knownNodes(List.of("send", "Alert"), List.of("Lobby", "staff"));

        assertEquals(List.of("admin", "command.send", "command.alert", "server.lobby", "server.staff"), nodes);
    }
}
