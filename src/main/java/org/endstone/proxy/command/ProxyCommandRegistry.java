package org.endstone.proxy.command;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ProxyCommandRegistry {
    private final Map<String, ProxyCommand> commands;

    public ProxyCommandRegistry(Collection<ProxyCommand> commands) {
        Map<String, ProxyCommand> byName = new LinkedHashMap<>();
        for (ProxyCommand command : commands) {
            byName.put(command.name().toLowerCase(Locale.ROOT), command);
        }
        this.commands = Map.copyOf(byName);
    }

    public static ProxyCommandRegistry defaults() {
        return new ProxyCommandRegistry(ProxyCommands.defaults());
    }

    public Collection<ProxyCommand> commands() {
        return commands.values();
    }

    public Optional<ProxyCommand> find(String commandLine) {
        String name = commandName(commandLine);
        if (name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(commands.get(name));
    }

    /**
     * The command name a client typed, without its leading slash, its arguments or its case.
     *
     * <p>Public because the interceptor has to read the name before it can decide who handles the
     * line — a name a backend has taken over is forwarded rather than looked up here — and two
     * copies of this parsing that disagreed would route a command one way and log it another.</p>
     */
    public static String commandName(String commandLine) {
        if (commandLine == null) {
            return "";
        }
        String command = commandLine.trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        int firstSpace = command.indexOf(' ');
        if (firstSpace >= 0) {
            command = command.substring(0, firstSpace);
        }
        return command.toLowerCase(Locale.ROOT);
    }
}
