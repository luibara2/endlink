package org.endstone.proxy.command;

import org.endstone.proxy.backend.BackendSwitcher;
import org.endstone.proxy.backend.ProxyConnection;

/** A player running a command from chat. */
final class PlayerCommandSender implements CommandSender {
    private final ProxyConnection connection;

    PlayerCommandSender(ProxyConnection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("connection cannot be null");
        }
        this.connection = connection;
    }

    @Override
    public String name() {
        return connection.clientLogin().authData().displayName();
    }

    @Override
    public String xuid() {
        return connection.clientLogin().authData().xuid();
    }

    @Override
    public boolean isConsole() {
        return false;
    }

    @Override
    public void sendMessage(String message) {
        BackendSwitcher.sendMessage(connection, message);
    }

    @Override
    public ProxyConnection connection() {
        return connection;
    }
}
