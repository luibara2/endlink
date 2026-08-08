package org.endstone.proxy.command;

import org.endstone.proxy.backend.ProxyConnection;

/**
 * Whoever ran a command, and where its output goes.
 *
 * <p>Exists so {@code /glist}, {@code /alert}, {@code /send} and {@code /perm} have one
 * implementation each rather than one for chat and one for the console. The console is deliberately
 * not a special case inside those commands — it is a sender that happens to be an administrator and
 * prints to stdout.</p>
 */
public interface CommandSender {
    String name();

    /** The XUID this sender is authorised as, or an empty string for the console. */
    String xuid();

    /** The console answers true and bypasses every permission check. */
    boolean isConsole();

    void sendMessage(String message);

    /** The player who ran the command, or null for the console. */
    default ProxyConnection connection() {
        return null;
    }

    static CommandSender console() {
        return ConsoleSender.INSTANCE;
    }

    static CommandSender of(ProxyConnection connection) {
        return new PlayerCommandSender(connection);
    }
}
