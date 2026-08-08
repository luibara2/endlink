package org.endstone.proxy.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proxy's only authorisation boundary, so the interesting cases are the ones where a player
 * could be mistaken for an administrator: a name that differs only in case, a blank identity, an
 * unconfigured proxy.
 */
class PermissionsConfigTest {
    @Test
    void withNoAdministratorsNobodyMayRunAnAdminCommand() {
        PermissionsConfig permissions = PermissionsConfig.defaults();

        assertFalse(permissions.allows("2535412345678901", "Steve", "send"));
        assertFalse(permissions.allows("2535412345678901", "Steve", "alert"));
        assertFalse(permissions.allows("2535412345678901", "Steve", "glist"));
        // The self-service commands stay open, or a fresh install has no way to move anyone.
        assertTrue(permissions.allows("2535412345678901", "Steve", "server"));
        assertTrue(permissions.allows("2535412345678901", "Steve", "hub"));
    }

    @Test
    void matchesAnAdministratorByXuid() {
        PermissionsConfig permissions = new PermissionsConfig(
                Set.of("2535412345678901"),
                PermissionsConfig.DEFAULT_ADMIN_COMMANDS,
                Set.of()
        );

        assertTrue(permissions.isAdmin("2535412345678901", "Steve"));
        assertTrue(permissions.allows("2535412345678901", "Steve", "send"));
        assertFalse(permissions.isAdmin("2535499999999999", "Steve"));
    }

    @Test
    void matchesAnAdministratorByGamertagRegardlessOfCase() {
        PermissionsConfig permissions = new PermissionsConfig(
                Set.of("OwnerName"),
                PermissionsConfig.DEFAULT_ADMIN_COMMANDS,
                Set.of()
        );

        assertTrue(permissions.isAdmin("2535412345678901", "ownername"));
        assertTrue(permissions.isAdmin("2535412345678901", "OWNERNAME"));
        assertFalse(permissions.isAdmin("2535412345678901", "ownername2"));
    }

    @Test
    void aBlankIdentityIsNeverAnAdministrator() {
        // "" would otherwise match an empty entry left in the config by a trailing comma.
        PermissionsConfig permissions = new PermissionsConfig(
                Set.of("owner", "", "  "),
                PermissionsConfig.DEFAULT_ADMIN_COMMANDS,
                Set.of()
        );

        assertFalse(permissions.isAdmin("", ""));
        assertFalse(permissions.isAdmin(null, null));
        assertTrue(permissions.isAdmin("owner", ""));
    }

    @Test
    void readsAdminsAndAdminCommandsFromProperties() {
        Properties properties = new Properties();
        properties.setProperty("permissions.admins", "2535412345678901, OwnerName ");
        properties.setProperty("permissions.adminCommands", "send,alert");

        PermissionsConfig permissions = PermissionsConfig.from(properties, List.of());

        assertTrue(permissions.isAdmin("2535412345678901", "Someone"));
        assertTrue(permissions.isAdmin("999", "ownername"));
        assertTrue(permissions.isAdminCommand("send"));
        // Dropped from the list, so it is open to everyone.
        assertFalse(permissions.isAdminCommand("glist"));
        assertTrue(permissions.allows("999", "Steve", "glist"));
    }

    @Test
    void stripsAnInlineCommentFromTheAdminList() {
        // Properties keeps "# owner" as part of the value, which would silently make the real XUID
        // unmatchable and lock the owner out of their own proxy.
        Properties properties = new Properties();
        properties.setProperty("permissions.admins", "2535412345678901  # the owner");

        PermissionsConfig permissions = PermissionsConfig.from(properties, List.of());

        assertTrue(permissions.isAdmin("2535412345678901", "Steve"));
    }

    @Test
    void anEmptyAdminCommandListOpensEverything() {
        Properties properties = new Properties();
        properties.setProperty("permissions.adminCommands", "");

        PermissionsConfig permissions = PermissionsConfig.from(properties, List.of());

        assertTrue(permissions.allows("999", "Steve", "send"));
        assertTrue(permissions.allows("999", "Steve", "alert"));
    }

    @Test
    void permCannotBeOpenedToEveryoneByConfig() {
        // Every config written before /perm existed omits it from permissions.adminCommands. If the
        // list were taken literally, the command that grants permissions would be open to every
        // player, and the first one to find it could make themselves an administrator.
        Properties properties = new Properties();
        properties.setProperty("permissions.adminCommands", "alert,glist,send");

        PermissionsConfig permissions = PermissionsConfig.from(properties, List.of());

        assertTrue(permissions.isAdminCommand("perm"));
        assertFalse(permissions.allows("999", "Steve", "perm"));
    }

    @Test
    void permStaysClosedEvenWithAnEmptyAdminCommandList() {
        Properties properties = new Properties();
        properties.setProperty("permissions.adminCommands", "");

        PermissionsConfig permissions = PermissionsConfig.from(properties, List.of());

        assertFalse(permissions.allows("999", "Steve", "perm"));
    }

    @Test
    void marksAConfiguredBackendAsAdminOnly() {
        Properties properties = new Properties();
        properties.setProperty("backend.staff.adminOnly", "true");
        properties.setProperty("backend.lobby.adminOnly", "false");
        properties.setProperty("permissions.admins", "owner");

        PermissionsConfig permissions = PermissionsConfig.from(properties, List.of("staff", "lobby", "default"));

        assertTrue(permissions.isAdminBackend("staff"));
        assertFalse(permissions.isAdminBackend("lobby"));
        assertFalse(permissions.isAdminBackend("default"));
        assertFalse(permissions.mayJoinBackend("999", "Steve", "staff"));
        assertTrue(permissions.mayJoinBackend("999", "owner", "staff"));
        assertTrue(permissions.mayJoinBackend("999", "Steve", "lobby"));
    }
}
