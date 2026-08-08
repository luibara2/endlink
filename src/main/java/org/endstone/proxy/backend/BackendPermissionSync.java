package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.data.PlayerPermission;
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket;

/** Reconciles the per-player permission fields sent by a Bedrock backend. */
final class BackendPermissionSync {
    private BackendPermissionSync() {
    }

    /**
     * Endstone/BDS can report an operator's individual command level as ADMIN while leaving the
     * player permission at the world's MEMBER default. The command level changes when the backend
     * ops or deops the player, so it is the backend-owned signal used to correct that contradiction.
     */
    static boolean apply(UpdateAbilitiesPacket packet) {
        if (packet == null
                || packet.getPlayerPermission() != PlayerPermission.MEMBER
                || !isOperatorCommandLevel(packet.getCommandPermission())) {
            return false;
        }
        packet.setPlayerPermission(PlayerPermission.OPERATOR);
        return true;
    }

    private static boolean isOperatorCommandLevel(CommandPermission permission) {
        return permission == CommandPermission.ADMIN
                || permission == CommandPermission.HOST
                || permission == CommandPermission.OWNER;
    }
}
