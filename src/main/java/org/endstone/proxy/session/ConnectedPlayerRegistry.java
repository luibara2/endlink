package org.endstone.proxy.session;

import org.endstone.proxy.backend.ProxyConnection;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ConnectedPlayerRegistry {
    public enum RegistrationResult {
        ACCEPTED,
        DUPLICATE_XUID,
        FULL
    }

    private final int maxPlayers;
    private final Map<String, ProxyConnection> connectionsByXuid = new HashMap<>();

    public ConnectedPlayerRegistry(int maxPlayers) {
        if (maxPlayers < 1) {
            throw new IllegalArgumentException("maxPlayers must be positive");
        }
        this.maxPlayers = maxPlayers;
    }

    public synchronized RegistrationResult register(ProxyConnection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("connection cannot be null");
        }

        String key = key(connection.clientLogin().authData().xuid());
        if (connectionsByXuid.containsKey(key)) {
            return RegistrationResult.DUPLICATE_XUID;
        }
        if (connectionsByXuid.size() >= maxPlayers) {
            return RegistrationResult.FULL;
        }

        connectionsByXuid.put(key, connection);
        return RegistrationResult.ACCEPTED;
    }

    public synchronized void unregister(ProxyConnection connection) {
        if (connection == null) {
            return;
        }
        connectionsByXuid.remove(key(connection.clientLogin().authData().xuid()), connection);
    }

    public synchronized int size() {
        return connectionsByXuid.size();
    }

    /**
     * A snapshot of everyone currently connected. Copied rather than exposed live: the callers are
     * commands that message each player in turn, and holding the registry's lock while writing to a
     * Netty channel is a good way to stall every other login.
     */
    public synchronized List<ProxyConnection> connections() {
        return List.copyOf(connectionsByXuid.values());
    }

    /**
     * Finds a connected player by gamertag, case-insensitively. Gamertags are unique on Xbox Live
     * and come from the Mojang-signed chain, so this cannot be pointed at someone else by choosing a
     * clever name.
     */
    public synchronized Optional<ProxyConnection> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim();
        for (ProxyConnection connection : connectionsByXuid.values()) {
            if (wanted.equalsIgnoreCase(connection.clientLogin().authData().displayName())) {
                return Optional.of(connection);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the real XUID (from the client's Mojang-signed chain) for a
     * currently-connected player matched by display name, or an empty string
     * if no such player is online. Used by the backend relay to inject real
     * XUIDs into outgoing PlayerListPacket entries — BDS in offline mode
     * (1.26.10+) leaves those blank because it does not trust self-signed
     * xid claims, breaking the client-side friends tab.
     */
    public synchronized String xuidByName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        for (ProxyConnection connection : connectionsByXuid.values()) {
            if (name.equalsIgnoreCase(connection.clientLogin().authData().displayName())) {
                return connection.clientLogin().authData().xuid();
            }
        }
        return "";
    }

    private static String key(String xuid) {
        return xuid.toLowerCase(Locale.ROOT);
    }
}
