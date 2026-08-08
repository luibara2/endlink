package org.endstone.proxy.permission;

import org.endstone.proxy.config.PermissionsConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Who may do what, as a live store rather than a restart.
 *
 * <p>{@link PermissionsConfig} remains the floor — whatever {@code config.properties} says is always
 * in force and cannot be revoked at runtime, so an operator locked out by a bad grant can always fix
 * it by editing the file. Everything granted through {@code /perm} lives on top of that and is
 * written to disk immediately, because the alternative is a permission that quietly disappears on
 * the next restart.</p>
 *
 * <h2>Nodes</h2>
 * <ul>
 *   <li>{@code admin} — everything, equivalent to being listed in {@code permissions.admins}</li>
 *   <li>{@code command.<name>} — one otherwise-restricted command, e.g. {@code command.send}</li>
 *   <li>{@code server.<name>} — one otherwise-restricted backend, e.g. {@code server.staff}</li>
 * </ul>
 *
 * <h2>Identity</h2>
 * <p>A subject is an XUID or a gamertag, matched case-insensitively against either — the same rule
 * the config list uses. XUIDs are the durable choice; a gamertag can be changed by its owner, and a
 * released one can be claimed by someone else. Granting by name is supported because it is what an
 * operator has to hand at the console, and because a player who has never connected has no XUID the
 * proxy knows.</p>
 *
 * <p>Reads happen on every command and from several event loops, so the map is guarded and handed
 * out only as copies.</p>
 */
public final class ProxyPermissions {
    public static final String ADMIN = "admin";
    public static final String COMMAND_PREFIX = "command.";
    public static final String SERVER_PREFIX = "server.";

    private final PermissionsConfig config;
    private final Path file;
    private final Map<String, Set<String>> grants = new TreeMap<>();

    ProxyPermissions(PermissionsConfig config, Path file) {
        this.config = config == null ? PermissionsConfig.defaults() : config;
        this.file = file;
    }

    /** An in-memory instance with nothing persisted, for tests and for the config-only path. */
    public static ProxyPermissions inMemory(PermissionsConfig config) {
        return new ProxyPermissions(config, null);
    }

    /**
     * Reads the grant file, creating nothing if it is absent — an empty store is the correct state
     * for a proxy that has never granted anything.
     */
    public static ProxyPermissions load(PermissionsConfig config, Path file) {
        ProxyPermissions permissions = new ProxyPermissions(config, file);
        if (file == null || Files.notExists(file)) {
            return permissions;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException exception) {
            // Refusing to start would take the whole network down over a permissions file; running
            // with config-only permissions is the safer failure, as long as it is loud.
            System.out.printf("WARNING: could not read %s (%s); running with config permissions only.%n",
                    file, exception.getMessage());
            return permissions;
        }
        for (String subject : properties.stringPropertyNames()) {
            Set<String> nodes = parseNodes(properties.getProperty(subject));
            if (!nodes.isEmpty()) {
                permissions.grants.put(normalize(subject), nodes);
            }
        }
        System.out.printf("Loaded runtime permissions for %d subject(s) from %s.%n",
                permissions.grants.size(), file);
        return permissions;
    }

    // ---------------------------------------------------------------- queries

    public boolean isAdmin(String xuid, String displayName) {
        return config.isAdmin(xuid, displayName) || hasNode(xuid, displayName, ADMIN);
    }

    public boolean isAdminCommand(String commandName) {
        return config.isAdminCommand(commandName);
    }

    public boolean isAdminBackend(String backendName) {
        return config.isAdminBackend(backendName);
    }

    /** Whether this player may run {@code commandName}. */
    public boolean allows(String xuid, String displayName, String commandName) {
        if (!config.isAdminCommand(commandName)) {
            return true;
        }
        return isAdmin(xuid, displayName)
                || hasNode(xuid, displayName, COMMAND_PREFIX + normalize(commandName));
    }

    /**
     * Whether this player may send <em>themselves</em> to a backend. Deliberately not consulted by
     * {@code /send}, failover or forced hosts — see {@link PermissionsConfig#mayJoinBackend}.
     */
    public boolean mayJoinBackend(String xuid, String displayName, String backendName) {
        if (!config.isAdminBackend(backendName)) {
            return true;
        }
        return isAdmin(xuid, displayName)
                || hasNode(xuid, displayName, SERVER_PREFIX + normalize(backendName));
    }

    /** Nodes granted at runtime to this subject, not counting anything the config gives them. */
    public synchronized Set<String> nodesOf(String subject) {
        return Set.copyOf(grants.getOrDefault(normalize(subject), Set.of()));
    }

    public synchronized Map<String, Set<String>> subjects() {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        grants.forEach((subject, nodes) -> copy.put(subject, Set.copyOf(nodes)));
        return copy;
    }

    /** What the config gives everyone matching, for {@code /perm info} to report alongside grants. */
    public PermissionsConfig config() {
        return config;
    }

    // -------------------------------------------------------------- mutations

    /** @return false when the subject already had the node, so callers can say so */
    public synchronized boolean grant(String subject, String node) {
        String key = normalize(subject);
        String value = normalize(node);
        requireUsable(key, value);
        boolean added = grants.computeIfAbsent(key, ignored -> new TreeSet<>()).add(value);
        if (added) {
            save();
        }
        return added;
    }

    /** @return false when the subject did not have the node */
    public synchronized boolean revoke(String subject, String node) {
        String key = normalize(subject);
        String value = normalize(node);
        Set<String> nodes = grants.get(key);
        if (nodes == null || !nodes.remove(value)) {
            return false;
        }
        if (nodes.isEmpty()) {
            grants.remove(key);
        }
        save();
        return true;
    }

    /** @return the number of nodes removed */
    public synchronized int revokeAll(String subject) {
        Set<String> removed = grants.remove(normalize(subject));
        if (removed == null || removed.isEmpty()) {
            return 0;
        }
        save();
        return removed.size();
    }

    /** The nodes that make sense to grant, for autocomplete and for rejecting typos. */
    public static List<String> knownNodes(Collection<String> commandNames, Collection<String> backendNames) {
        List<String> nodes = new ArrayList<>();
        nodes.add(ADMIN);
        for (String command : commandNames) {
            nodes.add(COMMAND_PREFIX + normalize(command));
        }
        for (String backend : backendNames) {
            nodes.add(SERVER_PREFIX + normalize(backend));
        }
        return List.copyOf(nodes);
    }

    // ---------------------------------------------------------------- interns

    private boolean hasNode(String xuid, String displayName, String node) {
        String wanted = normalize(node);
        synchronized (this) {
            return contains(grants.get(normalize(xuid)), wanted)
                    || contains(grants.get(normalize(displayName)), wanted);
        }
    }

    private static boolean contains(Set<String> nodes, String node) {
        // An admin grant answers for every node, so /perm set <player> admin needs no follow-up.
        return nodes != null && (nodes.contains(node) || nodes.contains(ADMIN));
    }

    private static void requireUsable(String subject, String node) {
        if (subject.isEmpty()) {
            throw new IllegalArgumentException("subject cannot be blank");
        }
        if (node.isEmpty()) {
            throw new IllegalArgumentException("node cannot be blank");
        }
        // A subject containing '=' or a node containing ',' would not survive the round trip
        // through the properties file, and would come back as a different permission.
        if (subject.indexOf('=') >= 0 || subject.indexOf(':') >= 0) {
            throw new IllegalArgumentException("subject cannot contain '=' or ':'");
        }
        if (node.indexOf(',') >= 0) {
            throw new IllegalArgumentException("node cannot contain ','");
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        Properties properties = new Properties();
        grants.forEach((subject, nodes) -> properties.setProperty(subject, String.join(",", nodes)));
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Written beside the target and moved into place: a half-written permissions file is
            // one that silently drops somebody's access on the next start.
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Runtime proxy permissions. Managed by /perm; config.properties still applies on top.");
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            System.out.printf("WARNING: could not write %s (%s); the change applies now but will be lost on restart.%n",
                    file, exception.getMessage());
        }
    }

    private static Set<String> parseNodes(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> nodes = new LinkedHashSet<>();
        for (String node : Arrays.asList(value.split(","))) {
            String normalized = normalize(node);
            if (!normalized.isEmpty()) {
                nodes.add(normalized);
            }
        }
        return new TreeSet<>(nodes);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
