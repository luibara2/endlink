package org.endstone.proxy.config;

import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.endstone.proxy.protocol.CanonicalProtocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

public record ProxyConfig(
        InetSocketAddress listenAddress,
        BackendConfig backend,
        Map<String, BackendConfig> backends,
        String hubBackendName,
        CanonicalProtocol backendProtocol,
        BackendVerificationConfig backendVerification,
        ProxyPolicy policy,
        String motd,
        String subMotd,
        String gameType,
        int maxPlayers,
        PacketCompressionAlgorithm compressionAlgorithm,
        int compressionThreshold,
        Path resourcePacksDir,
        boolean cacheBackendPacks,
        Path backendPackCacheDir,
        boolean crossBackendPalette,
        Path crossBackendPaletteCacheFile
) {
    private static final String DEFAULT_LISTEN_HOST = "0.0.0.0";
    private static final int DEFAULT_LISTEN_PORT = 19132;
    private static final String DEFAULT_BACKEND_HOST = "127.0.0.1";
    private static final int DEFAULT_BACKEND_PORT = 19133;

    public ProxyConfig {
        if (listenAddress == null) {
            throw new IllegalArgumentException("listenAddress cannot be null");
        }
        if (backend == null) {
            throw new IllegalArgumentException("backend cannot be null");
        }
        if (backends == null || backends.isEmpty()) {
            throw new IllegalArgumentException("backends cannot be empty");
        }
        if (hubBackendName == null || hubBackendName.isBlank()) {
            throw new IllegalArgumentException("hubBackendName cannot be blank");
        }
        if (backendVerification == null) {
            throw new IllegalArgumentException("backendVerification cannot be null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy cannot be null");
        }
        if (motd == null || motd.isBlank()) {
            throw new IllegalArgumentException("motd cannot be blank");
        }
        if (subMotd == null) {
            throw new IllegalArgumentException("subMotd cannot be null");
        }
        if (gameType == null || gameType.isBlank()) {
            throw new IllegalArgumentException("gameType cannot be blank");
        }
        if (maxPlayers < 1) {
            throw new IllegalArgumentException("maxPlayers must be positive");
        }
        if (compressionAlgorithm == null) {
            throw new IllegalArgumentException("compressionAlgorithm cannot be null");
        }
        if (compressionThreshold < 0) {
            throw new IllegalArgumentException("compressionThreshold cannot be negative");
        }
        // resourcePacksDir may be null (no packs configured)
    }

    public FailoverConfig failover() {
        return policy.failover();
    }

    public BackendSwitchConfig backendSwitch() {
        return policy.backendSwitch();
    }

    public PermissionsConfig permissions() {
        return policy.permissions();
    }

    public SecurityConfig security() {
        return policy.security();
    }

    public ForcedHostsConfig forcedHosts() {
        return policy.forcedHosts();
    }

    public JoinConfig join() {
        return policy.join();
    }

    public CommandsConfig commands() {
        return policy.commands();
    }

    /**
     * The documented config template, shipped inside the jar by {@code processResources}.
     *
     * <p>Only {@code endstone-proxy.jar} is uploaded to a backend, so a config generated on the
     * server is the only configuration documentation an operator ever sees there. It has to be the
     * commented, sectioned file rather than a {@link Properties#store} dump — that writes keys in
     * hash order with no explanation of any of them.
     */
    private static final String DEFAULT_CONFIG_RESOURCE = "/config.example.properties";

    public static ProxyConfig loadOrCreate(Path path) throws IOException {
        if (Files.notExists(path)) {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writeDefaultConfig(path);
        }

        // Deliberately re-read the file even on the run that just created it, so what is on disk and
        // what the proxy is running can never disagree.
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return from(properties, path.toAbsolutePath().getParent());
    }

    private static void writeDefaultConfig(Path path) throws IOException {
        try (InputStream template = ProxyConfig.class.getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
            if (template != null) {
                Files.copy(template, path);
                return;
            }
        }
        // The template is missing from the jar, which means the build dropped it. Still produce a
        // working config rather than refusing to start — an undocumented config beats no server.
        System.out.println("Config template " + DEFAULT_CONFIG_RESOURCE
                + " is missing from the jar; writing an undocumented default config instead.");
        try (OutputStream output = Files.newOutputStream(path)) {
            defaultProperties().store(output, "Endstone Bedrock proxy configuration");
        }
    }

    public static ProxyConfig from(Properties properties) {
        return from(properties, Path.of(".").toAbsolutePath());
    }

    public static ProxyConfig from(Properties properties, Path configDir) {
        String listenHost = properties.getProperty("listener.host", DEFAULT_LISTEN_HOST);
        int listenPort = intProperty(properties, "listener.port", DEFAULT_LISTEN_PORT);
        String backendName = properties.getProperty("backend.name", "default");
        String backendHost = properties.getProperty("backend.host", DEFAULT_BACKEND_HOST);
        int backendPort = intProperty(properties, "backend.port", DEFAULT_BACKEND_PORT);
        BackendConfig defaultBackend = new BackendConfig(
                backendName,
                new InetSocketAddress(backendHost, backendPort),
                backendProtocolFor(properties, backendName),
                dropSubChunkRequestsFor(properties, backendName)
        );
        Map<String, BackendConfig> backends = backendConfigs(properties, defaultBackend);
        String hubBackendName = properties.getProperty("hubBackend", backendName);
        CanonicalProtocol backendProtocol = CanonicalProtocol.fromConfig(properties.getProperty("backend.protocol", "auto"));
        boolean backendVerificationEnabled = booleanProperty(properties, "backendVerification.enabled", true);
        String backendVerificationHost = properties.getProperty("backendVerification.host", "127.0.0.1");
        int backendVerificationPort = intProperty(properties, "backendVerification.port", 19135);
        String defaultSubMotd = "Bedrock " + CanonicalProtocol.newest().minecraftVersion();

        String resourcePacksDirProp = properties.getProperty("resourcePacks.dir", "").trim();
        Path resourcePacksDir = null;
        if (!resourcePacksDirProp.isEmpty()) {
            Path resolved = configDir != null
                    ? configDir.resolve(resourcePacksDirProp)
                    : Path.of(resourcePacksDirProp);
            resourcePacksDir = resolved.toAbsolutePath().normalize();
        }

        boolean cacheBackendPacks = booleanProperty(properties, "resourcePacks.cacheBackendPacks", true);
        Path backendPackCacheDir = configDir != null
                ? configDir.resolve("cache").resolve("packs").toAbsolutePath().normalize()
                : null;
        boolean crossBackendPalette = booleanProperty(properties, "crossBackendPalette", true);
        Path crossBackendPaletteCacheFile = configDir != null
                ? configDir.resolve("cache").resolve("backend-palettes.nbt").toAbsolutePath().normalize()
                : null;

        FailoverConfig failover = failoverConfig(properties, backends, hubBackendName);
        return new ProxyConfig(
                new InetSocketAddress(listenHost, listenPort),
                defaultBackend,
                backends,
                hubBackendName,
                backendProtocol,
                new BackendVerificationConfig(
                        backendVerificationEnabled,
                        new InetSocketAddress(backendVerificationHost, backendVerificationPort),
                        properties.getProperty(
                                "backendVerification.sharedSecret",
                                BackendVerificationConfig.DEFAULT_SHARED_SECRET
                        ),
                        intProperty(properties, "backendVerification.pendingJoinTtlMillis", 15_000),
                        intProperty(properties, "backendVerification.requestSkewMillis", 30_000)
                ),
                new ProxyPolicy(
                        failover,
                        backendSwitchConfig(properties),
                        PermissionsConfig.from(properties, backends.values().stream()
                                .map(BackendConfig::name)
                                .toList()),
                        SecurityConfig.from(properties),
                        forcedHostsConfig(properties, backends),
                        JoinConfig.from(properties, failover),
                        CommandsConfig.from(properties, backends.values().stream()
                                .map(BackendConfig::name)
                                .toList())
                ),
                properties.getProperty("motd", "Endstone Proxy"),
                properties.getProperty("subMotd", defaultSubMotd),
                properties.getProperty("gameType", "Survival"),
                intProperty(properties, "maxPlayers", 20),
                compression(properties.getProperty("compression", "zlib")),
                intProperty(properties, "compressionThreshold", 0),
                resourcePacksDir,
                cacheBackendPacks,
                backendPackCacheDir,
                crossBackendPalette,
                crossBackendPaletteCacheFile
        );
    }

    public static Properties defaultProperties() {
        Properties properties = new Properties();
        properties.setProperty("listener.host", DEFAULT_LISTEN_HOST);
        properties.setProperty("listener.port", Integer.toString(DEFAULT_LISTEN_PORT));
        properties.setProperty("backend.name", "default");
        properties.setProperty("backend.host", DEFAULT_BACKEND_HOST);
        properties.setProperty("backend.port", Integer.toString(DEFAULT_BACKEND_PORT));
        properties.setProperty("backends", "default");
        properties.setProperty("hubBackend", "default");
        properties.setProperty("backend.protocol", "auto");
        properties.setProperty("backend.default.host", DEFAULT_BACKEND_HOST);
        properties.setProperty("backend.default.port", Integer.toString(DEFAULT_BACKEND_PORT));
        properties.setProperty("backendVerification.enabled", "true");
        properties.setProperty("backendVerification.host", "127.0.0.1");
        properties.setProperty("backendVerification.port", "19135");
        properties.setProperty("backendVerification.sharedSecret", BackendVerificationConfig.DEFAULT_SHARED_SECRET);
        properties.setProperty("backendVerification.pendingJoinTtlMillis", "15000");
        properties.setProperty("backendVerification.requestSkewMillis", "30000");
        properties.setProperty("failover.enabled", "true");
        properties.setProperty("failover.fallbacks", "default");
        properties.setProperty("failover.onBackendKick",
                BackendKickAction.AUTO.name().toLowerCase(java.util.Locale.ROOT));
        properties.setProperty("protocolFault.action",
                ProtocolFaultPolicy.Action.DISCONNECT.name().toLowerCase(java.util.Locale.ROOT));
        properties.setProperty("protocolFault.message", ProtocolFaultPolicy.DEFAULT_MESSAGE);
        properties.setProperty("protocolFault.logFile", ProtocolFaultPolicy.DEFAULT_LOG_FILE);
        BackendSwitchConfig switchDefaults = BackendSwitchConfig.defaults();
        properties.setProperty("switch.retryWindowMillis", Long.toString(switchDefaults.retryWindowMillis()));
        properties.setProperty("switch.retryDelayMillis", Long.toString(switchDefaults.retryDelayMillis()));
        properties.setProperty("switch.timeoutMillis", Long.toString(switchDefaults.timeoutMillis()));
        properties.setProperty("switch.connectTimeoutMillis", Long.toString(switchDefaults.connectTimeoutMillis()));
        properties.setProperty("permissions.admins", "");
        properties.setProperty("permissions.adminCommands", String.join(",",
                new java.util.TreeSet<>(PermissionsConfig.DEFAULT_ADMIN_COMMANDS)));
        properties.setProperty("commands.passthrough", "");
        properties.setProperty("commands.qualifier", CommandsConfig.DEFAULT_QUALIFIER);
        SecurityConfig securityDefaults = SecurityConfig.defaults();
        properties.setProperty("security.rateLimit.enabled", Boolean.toString(securityDefaults.rateLimitEnabled()));
        properties.setProperty("security.rateLimit.packetLimit", Integer.toString(securityDefaults.packetLimit()));
        properties.setProperty("security.rateLimit.globalPacketLimit",
                Integer.toString(securityDefaults.globalPacketLimit()));
        properties.setProperty("security.sendConnectionCookie",
                Boolean.toString(securityDefaults.sendConnectionCookie()));
        properties.setProperty("security.maxConnectionsPerAddress",
                Integer.toString(securityDefaults.maxConnectionsPerAddress()));
        properties.setProperty("security.maxConnectionAttempts",
                Integer.toString(securityDefaults.maxConnectionAttempts()));
        properties.setProperty("security.connectionAttemptWindowMillis",
                Long.toString(securityDefaults.connectionAttemptWindowMillis()));
        properties.setProperty("security.requireXuid", Boolean.toString(securityDefaults.requireXuid()));
        properties.setProperty("security.commandCooldownMillis",
                Long.toString(securityDefaults.commandCooldownMillis()));
        properties.setProperty("motd", "Endstone Proxy");
        properties.setProperty("subMotd", "Bedrock " + CanonicalProtocol.newest().minecraftVersion());
        properties.setProperty("gameType", "Survival");
        properties.setProperty("maxPlayers", "20");
        properties.setProperty("compression", "zlib");
        properties.setProperty("compressionThreshold", "0");
        properties.setProperty("resourcePacks.dir", "");
        properties.setProperty("resourcePacks.cacheBackendPacks", "true");
        properties.setProperty("crossBackendPalette", "true");
        return properties;
    }

    /**
     * A backend's own {@code backend.<name>.protocol}, or null to inherit the global setting. Kept
     * separate from the global value so "unset" stays distinguishable from "same as global" —
     * inheriting is resolved at connect time, not baked in here.
     */
    private static CanonicalProtocol backendProtocolFor(Properties properties, String name) {
        String value = properties.getProperty("backend." + name + ".protocol");
        if (value == null || value.isBlank()) {
            return null;
        }
        return CanonicalProtocol.fromConfig(ConfigValues.stripInlineComment(value));
    }

    /** See {@link BackendConfig#dropSubChunkRequests()} for why a backend would want this. */
    private static boolean dropSubChunkRequestsFor(Properties properties, String name) {
        return booleanProperty(properties, "backend." + name + ".dropSubChunkRequests", false);
    }

    private static int intProperty(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value.trim());
    }

    private static boolean booleanProperty(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static Map<String, BackendConfig> backendConfigs(Properties properties, BackendConfig defaultBackend) {
        Map<String, BackendConfig> backends = new LinkedHashMap<>();
        backends.put(normalize(defaultBackend.name()), defaultBackend);

        String backendNames = properties.getProperty("backends");
        if (backendNames == null || backendNames.isBlank()) {
            return Collections.unmodifiableMap(backends);
        }

        for (String name : Arrays.stream(backendNames.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new))) {
            String normalizedName = normalize(name);
            String host = properties.getProperty("backend." + name + ".host");
            String port = properties.getProperty("backend." + name + ".port");
            if (host == null && port == null && normalizedName.equals(normalize(defaultBackend.name()))) {
                backends.put(normalizedName, defaultBackend);
                continue;
            }
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("Missing backend." + name + ".host");
            }
            int parsedPort = intProperty(properties, "backend." + name + ".port", DEFAULT_BACKEND_PORT);
            backends.put(normalizedName, new BackendConfig(
                    name,
                    new InetSocketAddress(host, parsedPort),
                    backendProtocolFor(properties, name),
                    dropSubChunkRequestsFor(properties, name)
            ));
        }
        return Collections.unmodifiableMap(backends);
    }

    /**
     * Failover targets default to the hub backend, so a proxy that has never heard of
     * {@code failover.*} still returns players to the hub instead of kicking them when their
     * backend dies. {@code containsKey} rather than {@code getProperty}, because an explicitly
     * empty value is meaningful: it turns failover off for that backend.
     */
    private static FailoverConfig failoverConfig(
            Properties properties,
            Map<String, BackendConfig> backends,
            String hubBackendName
    ) {
        boolean enabled = booleanProperty(properties, "failover.enabled", true);
        List<String> fallbacks = properties.containsKey("failover.fallbacks")
                ? FailoverConfig.parseList(properties.getProperty("failover.fallbacks"))
                : List.of(hubBackendName);

        Map<String, List<String>> backendFallbacks = new LinkedHashMap<>();
        for (BackendConfig backend : backends.values()) {
            String key = "backend." + backend.name() + ".fallback";
            if (properties.containsKey(key)) {
                backendFallbacks.put(backend.name(), FailoverConfig.parseList(properties.getProperty(key)));
            }
        }
        // A protocol fault is not a backend outage and must not be treated as one; see
        // ProtocolFaultPolicy. containsKey rather than getProperty on the log file, because an
        // explicitly empty value means "keep the rule, drop the dedicated log".
        ProtocolFaultPolicy protocolFault = new ProtocolFaultPolicy(
                ProtocolFaultPolicy.parseAction(
                        properties.getProperty("protocolFault.action", ProtocolFaultPolicy.Action.DISCONNECT.name())),
                properties.getProperty("protocolFault.message", ProtocolFaultPolicy.DEFAULT_MESSAGE),
                properties.containsKey("protocolFault.logFile")
                        ? properties.getProperty("protocolFault.logFile")
                        : ProtocolFaultPolicy.DEFAULT_LOG_FILE
        );
        // A backend that kicks a player has made a decision about that player - a ban, a whitelist,
        // a moderation action. Moving them to a fallback overrides it, and because the fallback
        // usually transfers them straight back it also loops. Only a backend that went away without
        // saying anything is an outage worth rescuing from.
        BackendKickAction onBackendKick =
                BackendKickAction.parse(properties.getProperty("failover.onBackendKick"));
        return new FailoverConfig(enabled, fallbacks, backendFallbacks, protocolFault, onBackendKick);
    }

    /**
     * A forced host pointing at a backend that does not exist is a configuration mistake that would
     * otherwise only show up as players landing on the default backend for no visible reason, so it
     * is dropped loudly at load rather than looked up per join.
     */
    private static ForcedHostsConfig forcedHostsConfig(Properties properties, Map<String, BackendConfig> backends) {
        ForcedHostsConfig configured = ForcedHostsConfig.from(properties);
        Map<String, String> known = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : configured.byHostname().entrySet()) {
            if (backends.containsKey(normalize(entry.getValue()))) {
                known.put(entry.getKey(), entry.getValue());
            } else {
                System.out.printf(
                        "WARNING: ignoring forcedHost.%s=%s because no backend named '%s' is configured.%n",
                        entry.getKey(),
                        entry.getValue(),
                        entry.getValue()
                );
            }
        }
        return new ForcedHostsConfig(known);
    }

    private static BackendSwitchConfig backendSwitchConfig(Properties properties) {
        BackendSwitchConfig defaults = BackendSwitchConfig.defaults();
        return new BackendSwitchConfig(
                intProperty(properties, "switch.retryWindowMillis", (int) defaults.retryWindowMillis()),
                intProperty(properties, "switch.retryDelayMillis", (int) defaults.retryDelayMillis()),
                intProperty(properties, "switch.timeoutMillis", (int) defaults.timeoutMillis()),
                intProperty(properties, "switch.connectTimeoutMillis", (int) defaults.connectTimeoutMillis())
        );
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static PacketCompressionAlgorithm compression(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "none" -> PacketCompressionAlgorithm.NONE;
            case "snappy" -> PacketCompressionAlgorithm.SNAPPY;
            case "zlib" -> PacketCompressionAlgorithm.ZLIB;
            default -> throw new IllegalArgumentException("Unsupported compression algorithm: " + value);
        };
    }
}
