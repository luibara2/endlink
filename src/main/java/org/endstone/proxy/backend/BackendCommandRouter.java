package org.endstone.proxy.backend;

import org.endstone.proxy.command.CommandArguments;
import org.endstone.proxy.command.CommandInterception;
import org.endstone.proxy.command.CommandSender;
import org.endstone.proxy.command.NetworkCommands;
import org.endstone.proxy.config.BackendConfig;
import org.endstone.proxy.config.SecurityConfig;
import org.endstone.proxy.permission.ProxyPermissions;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Runs a proxy command a player typed in chat.
 *
 * <p>Only the commands that act on the caller's own session live here. Everything that acts on the
 * network — {@code /send}, {@code /glist}, {@code /alert}, {@code /perm} — is in
 * {@link NetworkCommands}, so the console runs the same code.</p>
 */
public final class BackendCommandRouter {
    private final BackendDirectory backendDirectory;
    private final BackendSwitcher switcher;
    private final NetworkCommands networkCommands;
    private final ProxyPermissions permissions;
    private final SecurityConfig security;

    public BackendCommandRouter(
            BackendDirectory backendDirectory,
            BackendSwitcher switcher,
            NetworkCommands networkCommands,
            ProxyPermissions permissions,
            SecurityConfig security
    ) {
        this.backendDirectory = backendDirectory;
        this.switcher = switcher;
        this.networkCommands = networkCommands;
        this.permissions = permissions;
        this.security = security == null ? SecurityConfig.defaults() : security;
    }

    public void execute(ProxyConnection connection, CommandInterception.Consumed command) {
        String name = command.command().name();
        CommandSender sender = CommandSender.of(connection);

        if (!networkCommands.authorize(sender, name)) {
            return;
        }
        // Administrators are exempt: the cooldown exists so an unattended macro cannot turn one
        // player into a connection flood against a backend, and someone who can already move the
        // whole network is not that threat.
        boolean admin = permissions.isAdmin(sender.xuid(), sender.name());
        if (!admin && !connection.claimProxyCommandSlot(security.commandCooldownMillis())) {
            sender.sendMessage("You are using proxy commands too quickly. Try again in a moment.");
            return;
        }

        List<String> arguments = CommandArguments.split(command.originalCommandLine());
        switch (name) {
            case "hub", "lobby" -> selfServiceSwitch(connection, backendDirectory.hubBackend());
            case "server" -> server(connection, arguments);
            case "glist" -> networkCommands.glist(sender);
            case "send" -> networkCommands.send(sender, arguments);
            case "alert" -> networkCommands.alert(sender, CommandArguments.remainder(command.originalCommandLine()));
            case "perm" -> networkCommands.permission(sender, arguments);
            default -> sender.sendMessage("Unknown proxy command: " + name);
        }
    }

    private void server(ProxyConnection connection, List<String> arguments) {
        if (arguments.isEmpty()) {
            BackendSwitcher.sendMessage(connection, "Servers: " + backendDirectory.backends().stream()
                    .map(BackendConfig::name)
                    .filter(name -> mayJoin(connection, name))
                    .collect(Collectors.joining(", ")));
            BackendSwitcher.sendMessage(connection,
                    "You are on " + connection.backendName() + ". Use /server <name> to switch.");
            return;
        }
        backendDirectory.find(arguments.get(0))
                .ifPresentOrElse(
                        backend -> selfServiceSwitch(connection, backend),
                        () -> BackendSwitcher.sendMessage(connection, "Unknown server: " + arguments.get(0))
                );
    }

    /**
     * A switch the player asked for themselves, which a restricted backend refuses.
     *
     * <p>Reported as "unknown" rather than "not allowed": a player who cannot reach a backend has no
     * business learning it exists, and the command tree omits it for the same reason. An
     * administrator moving someone with {@code /send} goes through the switcher directly and is not
     * subject to this — that is the point of a staff-only backend.</p>
     */
    private void selfServiceSwitch(ProxyConnection connection, BackendConfig backend) {
        if (!mayJoin(connection, backend.name())) {
            System.out.printf(
                    "Refused %s (%s) self-service access to restricted backend %s.%n",
                    connection.clientLogin().authData().displayName(),
                    connection.clientLogin().authData().xuid(),
                    backend.name()
            );
            BackendSwitcher.sendMessage(connection, "Unknown server: " + backend.name());
            return;
        }
        switcher.switchBackend(connection, backend);
    }

    private boolean mayJoin(ProxyConnection connection, String backendName) {
        return permissions.mayJoinBackend(
                connection.clientLogin().authData().xuid(),
                connection.clientLogin().authData().displayName(),
                backendName
        );
    }
}
