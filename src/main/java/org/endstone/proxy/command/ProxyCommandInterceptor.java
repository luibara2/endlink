package org.endstone.proxy.command;

import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket;
import org.endstone.proxy.config.CommandsConfig;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a command line the client sent is the proxy's to run or the backend's.
 *
 * <p>One of these is built per backend connection, because the answer is per backend: a hub running
 * a plugin that owns {@code /hub} and {@code /server} passes both through, while the same two names
 * on a minigame backend are the proxy's. See {@link org.endstone.proxy.config.CommandsConfig} for
 * the setting and for why the qualified form exists.</p>
 */
public final class ProxyCommandInterceptor {
    private final ProxyCommandRegistry registry;
    private final Set<String> passthrough;
    private final String qualifier;

    /** Keeps every command for the proxy, with the default qualified form still available. */
    public ProxyCommandInterceptor(ProxyCommandRegistry registry) {
        this(registry, Set.of(), CommandsConfig.DEFAULT_QUALIFIER);
    }

    /**
     * @param passthrough command names this backend has taken over, which are forwarded unchanged
     * @param qualifier   the prefix that forces proxy handling regardless, or empty to disable it
     */
    public ProxyCommandInterceptor(ProxyCommandRegistry registry, Set<String> passthrough, String qualifier) {
        if (registry == null) {
            throw new IllegalArgumentException("registry cannot be null");
        }
        this.registry = registry;
        this.passthrough = passthrough == null ? Set.of() : Set.copyOf(passthrough);
        this.qualifier = qualifier == null ? "" : qualifier.trim().toLowerCase(Locale.ROOT);
    }

    public CommandInterception intercept(CommandRequestPacket packet) {
        String commandLine = packet.getCommand();
        String name = ProxyCommandRegistry.commandName(commandLine);

        // An empty qualifier disables the qualified form rather than making every command qualified,
        // which is what a startsWith("") test would otherwise do.
        boolean qualified = !qualifier.isEmpty() && name.startsWith(qualifier);
        String lookup = qualified ? name.substring(qualifier.length()) : name;

        Optional<ProxyCommand> command = registry.find(lookup);
        if (command.isEmpty()) {
            // Includes a qualified name the proxy does not have: forwarding lets the backend give
            // its own "unknown command" rather than the proxy inventing one for a name it never
            // advertised.
            return new CommandInterception.Forward(commandLine);
        }
        if (!qualified && passthrough.contains(lookup)) {
            return new CommandInterception.Forward(commandLine);
        }
        // The original line is carried through unchanged, qualifier and all: CommandArguments cuts
        // at the first whitespace whatever the name is, so `/proxy:server skygen` yields the same
        // arguments as `/server skygen`.
        return new CommandInterception.Consumed(command.get(), commandLine);
    }
}
