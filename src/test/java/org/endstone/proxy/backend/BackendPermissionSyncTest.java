package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.data.PlayerPermission;
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendPermissionSyncTest {
    @Test
    void promotesBackendAdminsToOperator() {
        UpdateAbilitiesPacket packet = abilities(PlayerPermission.MEMBER, CommandPermission.ADMIN);

        assertTrue(BackendPermissionSync.apply(packet));
        assertEquals(PlayerPermission.OPERATOR, packet.getPlayerPermission());
    }

    @Test
    void promotesBackendHostsAndOwnersToOperator() {
        UpdateAbilitiesPacket host = abilities(PlayerPermission.MEMBER, CommandPermission.HOST);
        UpdateAbilitiesPacket owner = abilities(PlayerPermission.MEMBER, CommandPermission.OWNER);

        assertTrue(BackendPermissionSync.apply(host));
        assertTrue(BackendPermissionSync.apply(owner));
        assertEquals(PlayerPermission.OPERATOR, host.getPlayerPermission());
        assertEquals(PlayerPermission.OPERATOR, owner.getPlayerPermission());
    }

    @Test
    void leavesOrdinaryMembersAsMembers() {
        UpdateAbilitiesPacket packet = abilities(PlayerPermission.MEMBER, CommandPermission.ANY);

        assertFalse(BackendPermissionSync.apply(packet));
        assertEquals(PlayerPermission.MEMBER, packet.getPlayerPermission());
    }

    @Test
    void preservesExplicitVisitorAndCustomPermissions() {
        UpdateAbilitiesPacket visitor = abilities(PlayerPermission.VISITOR, CommandPermission.ADMIN);
        UpdateAbilitiesPacket custom = abilities(PlayerPermission.CUSTOM, CommandPermission.ADMIN);

        assertFalse(BackendPermissionSync.apply(visitor));
        assertFalse(BackendPermissionSync.apply(custom));
        assertEquals(PlayerPermission.VISITOR, visitor.getPlayerPermission());
        assertEquals(PlayerPermission.CUSTOM, custom.getPlayerPermission());
    }

    @Test
    void leavesAnAlreadyConsistentOperatorUnchanged() {
        UpdateAbilitiesPacket packet = abilities(PlayerPermission.OPERATOR, CommandPermission.ADMIN);

        assertFalse(BackendPermissionSync.apply(packet));
        assertEquals(PlayerPermission.OPERATOR, packet.getPlayerPermission());
    }

    private static UpdateAbilitiesPacket abilities(
            PlayerPermission playerPermission,
            CommandPermission commandPermission
    ) {
        UpdateAbilitiesPacket packet = new UpdateAbilitiesPacket();
        packet.setPlayerPermission(playerPermission);
        packet.setCommandPermission(commandPermission);
        return packet;
    }
}
