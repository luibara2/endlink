package org.endstone.proxy.config;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Who may run which proxy command.
 *
 * <p>Deliberately two-level rather than a general permission system: a command is either open to
 * everyone or restricted to administrators, and administrators are a flat list. That is enough to
 * keep {@code /send} and {@code /alert} away from players without inventing a group model the proxy
 * has nowhere to store. Backends work the same way — {@code backend.<name>.adminOnly=true} keeps one
 * out of reach of {@code /server}.</p>
 *
 * <p><b>This is the proxy's only authorisation boundary, so it is checked on execution, not on
 * display.</b> Hiding a command from the client's command tree stops it appearing in autocomplete;
 * it does not stop anyone sending the packet by hand. {@code BackendCommandRouter} re-checks every
 * invocation for that reason.</p>
 *
 * <p>Administrators are matched by XUID <em>or</em> gamertag, both case-insensitively. Prefer XUIDs:
 * both come from the client's Mojang-signed login chain and neither can be forged, but a gamertag
 * can be changed by its owner and a released one can later be claimed by someone else, whereas an
 * XUID is permanent.</p>
 */
public record PermissionsConfig(Set<String> admins, Set<String> adminCommands, Set<String> adminBackends) {
    /**
     * Commands restricted to administrators unless {@code permissions.adminCommands} says otherwise.
     * {@code /glist} is included because it reports where every player on the network is, which is
     * exactly what someone probing for a staff-only backend wants.
     */
    public static final Set<String> DEFAULT_ADMIN_COMMANDS = Set.of("send", "alert", "glist", "perm");

    /**
     * Commands that stay administrator-only whatever the config says, because opening them hands
     * over the proxy itself rather than one capability.
     */
    public static final Set<String> ALWAYS_ADMIN = Set.of("perm");

    public PermissionsConfig {
        admins = lowercased(admins);
        adminCommands = lowercased(adminCommands);
        adminBackends = lowercased(adminBackends);
    }

    /** No administrators, so every admin command is unavailable to everyone until one is configured. */
    public static PermissionsConfig defaults() {
        return new PermissionsConfig(Set.of(), DEFAULT_ADMIN_COMMANDS, Set.of());
    }

    public static PermissionsConfig from(Properties properties, Collection<String> backendNames) {
        Set<String> admins = new LinkedHashSet<>(
                ConfigValues.commaList(properties.getProperty("permissions.admins"), "permissions.admins")
        );
        Set<String> adminCommands = properties.containsKey("permissions.adminCommands")
                ? new LinkedHashSet<>(ConfigValues.commaList(
                        properties.getProperty("permissions.adminCommands"),
                        "permissions.adminCommands"))
                : DEFAULT_ADMIN_COMMANDS;

        Set<String> adminBackends = new LinkedHashSet<>();
        for (String backendName : backendNames) {
            String value = properties.getProperty("backend." + backendName + ".adminOnly");
            if (value != null && Boolean.parseBoolean(ConfigValues.stripInlineComment(value))) {
                adminBackends.add(backendName);
            }
        }
        return new PermissionsConfig(admins, adminCommands, adminBackends);
    }

    public boolean isAdmin(String xuid, String displayName) {
        return admins.contains(normalize(xuid)) || admins.contains(normalize(displayName));
    }

    public boolean isAdminCommand(String commandName) {
        // ALWAYS_ADMIN wins over the configured list, in both directions: a config that omits
        // /perm — as every config written before /perm existed does — would otherwise leave the
        // command that grants permissions open to everyone, and the first player to find it could
        // make themselves an administrator. There is no legitimate reason to open it, so it is not
        // expressible.
        String normalized = normalize(commandName);
        return ALWAYS_ADMIN.contains(normalized) || adminCommands.contains(normalized);
    }

    /** Whether the player identified by {@code xuid}/{@code displayName} may run {@code commandName}. */
    public boolean allows(String xuid, String displayName, String commandName) {
        return !isAdminCommand(commandName) || isAdmin(xuid, displayName);
    }

    public boolean isAdminBackend(String backendName) {
        return adminBackends.contains(normalize(backendName));
    }

    /**
     * Whether this player may send <em>themselves</em> to a backend — with {@code /server},
     * {@code /hub} or {@code /lobby}.
     *
     * <p>Restricted backends are also hidden from the {@code /server} listing and from the command
     * tree's backend enum, so a player has no way to learn one exists. Two paths deliberately do not
     * consult this, because both are the operator's decision rather than the player's: an
     * administrator moving someone with {@code /send}, and the proxy placing a player by failover or
     * forced host. Do not list a restricted backend as a failover target or a forced host unless you
     * mean anyone to be able to land there.</p>
     */
    public boolean mayJoinBackend(String xuid, String displayName, String backendName) {
        return !isAdminBackend(backendName) || isAdmin(xuid, displayName);
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
