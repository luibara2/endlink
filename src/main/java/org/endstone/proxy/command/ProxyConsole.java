package org.endstone.proxy.command;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Reads commands from the proxy's own terminal.
 *
 * <p>Runs on a daemon thread so a proxy started without a console — under {@code nohup}, as a
 * service, with stdin closed — simply sees end-of-stream and carries on serving players rather than
 * holding the JVM open on a read that will never return.</p>
 *
 * <p>The console is an administrator by definition (see {@link ConsoleSender}), which makes it the
 * way out of a proxy nobody can administer: a fresh install with no {@code permissions.admins} entry
 * can grant the first {@code admin} node from here.</p>
 */
public final class ProxyConsole {
    private final NetworkCommands networkCommands;
    private final Runnable shutdown;
    private final InputStream input;
    private volatile boolean running;
    private Thread thread;

    public ProxyConsole(NetworkCommands networkCommands, Runnable shutdown) {
        this(networkCommands, shutdown, System.in);
    }

    ProxyConsole(NetworkCommands networkCommands, Runnable shutdown, InputStream input) {
        this.networkCommands = networkCommands;
        this.shutdown = shutdown;
        this.input = input;
    }

    public void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(this::readLoop, "proxy-console");
        thread.setDaemon(true);
        thread.start();
        System.out.println("Console ready. Type 'help' for commands.");
    }

    public void stop() {
        running = false;
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                try {
                    execute(line);
                } catch (Exception exception) {
                    // One bad command must not take the console down for the rest of the run.
                    System.out.printf("Command failed: %s: %s%n",
                            exception.getClass().getSimpleName(), exception.getMessage());
                }
            }
        } catch (IOException exception) {
            System.out.printf("Console closed: %s.%n", exception.getMessage());
        }
    }

    /** Visible for tests. Accepts a leading slash so pasting an in-game command works. */
    void execute(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        CommandSender sender = CommandSender.console();
        String trimmed = line.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        // CommandArguments drops the leading command word, exactly as it does for a chat command.
        String command = trimmed.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        List<String> arguments = CommandArguments.split(trimmed);

        switch (command) {
            case "help", "?" -> help(sender);
            case "glist", "list" -> networkCommands.glist(sender);
            case "send" -> networkCommands.send(sender, arguments);
            case "alert", "say" -> networkCommands.alert(sender, CommandArguments.remainder(trimmed));
            case "perm", "permission" -> networkCommands.permission(sender, arguments);
            case "stop", "end" -> {
                sender.sendMessage("Stopping the proxy.");
                shutdown.run();
            }
            default -> sender.sendMessage("Unknown command: " + command + ". Type 'help' for commands.");
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage("glist                      - who is online, and where");
        sender.sendMessage("send <player|all> <server> - move a player");
        sender.sendMessage("alert <message>            - broadcast to everyone");
        sender.sendMessage("perm set <player> <node>   - grant a permission");
        sender.sendMessage("perm unset <player> <node> - revoke a permission");
        sender.sendMessage("perm info <player>         - what a player may do");
        sender.sendMessage("perm list                  - every runtime grant");
        sender.sendMessage("stop                       - shut the proxy down");
        sender.sendMessage("Nodes: " + String.join(", ", networkCommands.knownNodes()));
    }
}
