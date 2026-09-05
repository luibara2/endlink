package org.endstone.proxy.config;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Which of the proxy's own command names a backend is allowed to keep for itself.
 *
 * <p>The proxy and a backend plugin routinely want the same name. A hub plugin's {@code /hub} is an
 * in-world teleport to spawn and its {@code /server} opens a selector form; on a minigame backend
 * with no such plugin the same two names have to mean the proxy's backend switch. Without this
 * setting the proxy consumes both everywhere, the backend never sees the packet, and the client
 * still renders the backend's description for a command the proxy answers — autocomplete from one
 * server, behaviour from another.</p>
 *
 * <p>A name listed for a backend is <em>forwarded</em> while the player is on that backend, and is
 * left out of the command tree the proxy injects there, so the backend's own registration is the
 * only one the client ever sees. Everywhere else the proxy keeps the name.</p>
 *
 * <h2>The qualified form</h2>
 *
 * <p>{@code /proxy:hub} and {@code /proxy:server} reach the proxy's implementation whatever a
 * backend does with the bare name. They are never forwarded and never injected into the command
 * tree, so they do not appear in autocomplete, in {@code /help}, or in the client's parser — a
 * player has to know the name to type it. That makes them the way back in: hand every name on the
 * network to the backends and the proxy is still reachable, which is why {@code /proxy:perm} exists
 * even for an operator who has passed {@code perm} through by mistake.</p>
 *
 * <p>Hidden is not the same as privileged. {@code BackendCommandRouter} authorises the qualified
 * form exactly as it authorises the bare one, so {@code /proxy:alert} is still refused to a player
 * who may not run {@code /alert}. The prefix decides <em>who handles the command</em>, never who may
 * run it.</p>
 *
 * @param enabled            whether Endlink advertises and handles any proxy commands
 * @param passthrough        names every backend keeps, for backends with no list of their own
 * @param backendPassthrough per-backend lists, which replace {@code passthrough} rather than adding
 *                           to it — the same "an explicit value wins outright" rule as
 *                           {@code backend.<name>.protocol} and {@code backend.<name>.fallback}
 * @param qualifier          the prefix that forces proxy handling, or empty to disable it
 */
public record CommandsConfig(
        boolean enabled,
        Set<String> passthrough,
        Map<String, Set<String>> backendPassthrough,
        String qualifier
) {
    public static final String DEFAULT_QUALIFIER = "proxy:";

    public CommandsConfig {
        passthrough = lowercased(passthrough);
        Map<String, Set<String>> normalized = new LinkedHashMap<>();
        if (backendPassthrough != null) {
            backendPassthrough.forEach((backend, commands) ->
                    normalized.put(normalize(backend), lowercased(commands)));
        }
        backendPassthrough = Map.copyOf(normalized);
        qualifier = normalize(qualifier);
    }

    /** The proxy keeps every name on every backend, and the qualified form is available. */
    public static CommandsConfig defaults() {
        return new CommandsConfig(true, Set.of(), Map.of(), DEFAULT_QUALIFIER);
    }

    public static CommandsConfig from(Properties properties, Collection<String> backendNames) {
        boolean enabled = !properties.containsKey("commands.enabled")
                || Boolean.parseBoolean(ConfigValues.stripInlineComment(
                        properties.getProperty("commands.enabled")));
        Set<String> passthrough = properties.containsKey("commands.passthrough")
                ? new LinkedHashSet<>(ConfigValues.commaList(
                        properties.getProperty("commands.passthrough"), "commands.passthrough"))
                : Set.of();

        Map<String, Set<String>> backendPassthrough = new LinkedHashMap<>();
        for (String backendName : backendNames) {
            String key = "backend." + backendName + ".passthroughCommands";
            // containsKey rather than getProperty, because an explicitly empty value is meaningful:
            // it means this backend passes nothing through even when commands.passthrough is set.
            if (properties.containsKey(key)) {
                backendPassthrough.put(
                        backendName,
                        new LinkedHashSet<>(ConfigValues.commaList(properties.getProperty(key), key))
                );
            }
        }

        String qualifier = properties.containsKey("commands.qualifier")
                ? ConfigValues.stripInlineComment(properties.getProperty("commands.qualifier"))
                : DEFAULT_QUALIFIER;
        return new CommandsConfig(enabled, passthrough, backendPassthrough, qualifier);
    }

    /** The names this backend keeps for itself. */
    public Set<String> passthroughFor(String backendName) {
        Set<String> configured = backendPassthrough.get(normalize(backendName));
        return configured != null ? configured : passthrough;
    }

    /** Whether {@code commandName} reaches the backend while the player is on {@code backendName}. */
    public boolean isPassthrough(String backendName, String commandName) {
        return passthroughFor(backendName).contains(normalize(commandName));
    }

    /**
     * Whether any backend passes anything through — used only to decide whether the startup
     * diagnostics are worth printing.
     */
    public boolean isEmpty() {
        return passthrough.isEmpty() && backendPassthrough.values().stream().allMatch(Set::isEmpty);
    }

    private static Set<String> lowercased(Collection<String> values) {
        if (values == null) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(normalize(value));
            }
        }
        return Set.copyOf(normalized);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
