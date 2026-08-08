package org.endstone.proxy.command;

import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumConstraint;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumData;
import org.cloudburstmc.protocol.bedrock.data.command.SoftEnumUpdateType;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSoftEnumPacket;
import org.endstone.proxy.backend.ProxyConnection;
import org.endstone.proxy.permission.ProxyPermissions;
import org.endstone.proxy.session.ConnectedPlayerRegistry;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The list of names {@code /send} autocompletes: everyone on the network, plus {@code all}.
 *
 * <p>A <em>soft</em> enum, because the roster changes constantly and
 * {@code AvailableCommandsPacket} is sent once when a player joins. Soft enums exist for exactly
 * this — the client accepts {@link UpdateSoftEnumPacket} afterwards, so the values can be replaced
 * without rebuilding and resending the whole command tree.</p>
 *
 * <p>Being an enum also keeps it clear of the codec's parameter type table, which is not trustworthy
 * on this protocol: an enum parameter's wire value indexes a table carried inside the packet itself.
 * That is the same reason {@code /server}'s backend argument was never at risk when {@code /alert}'s
 * crashed the client.</p>
 */
public final class ProxyPlayerEnum {
    public static final String NAME = "ProxyPlayers";
    /** Velocity's spelling, and what an admin reaches for when moving the whole network. */
    public static final String ALL = "all";

    private final ConnectedPlayerRegistry connectedPlayers;
    private final ProxyPermissions permissions;

    public ProxyPlayerEnum(ConnectedPlayerRegistry connectedPlayers, ProxyPermissions permissions) {
        this.connectedPlayers = connectedPlayers;
        this.permissions = permissions;
    }

    /** A snapshot for the command tree, taken when a player's tree is built. */
    public CommandEnumData snapshot() {
        return new CommandEnumData(NAME, values(), true);
    }

    /**
     * Replaces the enum on every client whose command tree contains it.
     *
     * <p>Sent only to administrators: nobody else was given {@code /send}, so nobody else has a
     * {@code ProxyPlayers} enum for the update to land in. It also means a player cannot learn who
     * is online from a packet they were never meant to receive.</p>
     */
    public void broadcast() {
        if (connectedPlayers == null) {
            return;
        }
        CommandEnumData updated = snapshot();
        for (ProxyConnection connection : connectedPlayers.connections()) {
            if (!mayReceive(connection)) {
                continue;
            }
            UpdateSoftEnumPacket packet = new UpdateSoftEnumPacket();
            packet.setType(SoftEnumUpdateType.REPLACE);
            packet.setSoftEnum(updated);
            connection.client().sendPacket(packet);
        }
    }

    private boolean mayReceive(ProxyConnection connection) {
        if (!connection.client().isConnected() || !connection.hasClientJoinedWorld()) {
            return false;
        }
        return permissions.allows(
                connection.clientLogin().authData().xuid(),
                connection.clientLogin().authData().displayName(),
                "send"
        );
    }

    private Map<String, Set<CommandEnumConstraint>> values() {
        Map<String, Set<CommandEnumConstraint>> values = new LinkedHashMap<>();
        values.put(ALL, EnumSet.noneOf(CommandEnumConstraint.class));
        if (connectedPlayers == null) {
            return values;
        }
        for (ProxyConnection connection : connectedPlayers.connections()) {
            String name = connection.clientLogin().authData().displayName();
            if (name != null && !name.isBlank()) {
                values.put(name, EnumSet.noneOf(CommandEnumConstraint.class));
            }
        }
        return values;
    }
}
