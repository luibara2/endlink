package org.endstone.proxy.backend;

import org.endstone.proxy.config.BackendConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class BackendDirectory {
    private final Map<String, BackendConfig> backends;
    private final String defaultBackendName;
    private final String hubBackendName;

    public BackendDirectory(Map<String, BackendConfig> backends, String defaultBackendName, String hubBackendName) {
        if (backends == null || backends.isEmpty()) {
            throw new IllegalArgumentException("backends cannot be empty");
        }
        this.backends = normalized(backends);
        this.defaultBackendName = normalize(defaultBackendName);
        this.hubBackendName = normalize(hubBackendName);
        if (!this.backends.containsKey(this.defaultBackendName)) {
            throw new IllegalArgumentException("default backend is not configured: " + defaultBackendName);
        }
        if (!this.backends.containsKey(this.hubBackendName)) {
            throw new IllegalArgumentException("hub backend is not configured: " + hubBackendName);
        }
    }

    public BackendConfig defaultBackend() {
        return backends.get(defaultBackendName);
    }

    public BackendConfig hubBackend() {
        return backends.get(hubBackendName);
    }

    public Optional<BackendConfig> find(String name) {
        return Optional.ofNullable(backends.get(normalize(name)));
    }

    /**
     * Finds the configured backend addressed by a Bedrock {@code TransferPacket}.
     *
     * <p>Only endpoints already present in the proxy configuration qualify. Hostnames are matched
     * case-insensitively (and without a trailing DNS dot), while a backend whose configured address
     * was resolved at startup may also be addressed by its numeric IP. Resolving an arbitrary host
     * supplied by a backend here would block the Netty packet thread, so aliases which are neither
     * the configured hostname nor its resolved numeric address deliberately fall through to the
     * normal client-side transfer.</p>
     */
    public Optional<BackendConfig> findByAddress(String host, int port) {
        if (host == null || host.isBlank() || port < 1 || port > 65_535) {
            return Optional.empty();
        }
        String normalizedHost = normalizeHost(host);
        return backends.values().stream()
                .filter(backend -> backend.address().getPort() == port)
                .filter(backend -> matchesHost(backend.address(), normalizedHost))
                .findFirst();
    }

    public Collection<BackendConfig> backends() {
        return backends.values();
    }

    public Collection<String> backendNames() {
        return backends.values().stream()
                .map(BackendConfig::name)
                .toList();
    }

    private static Map<String, BackendConfig> normalized(Map<String, BackendConfig> input) {
        Map<String, BackendConfig> result = new LinkedHashMap<>();
        for (BackendConfig backend : input.values()) {
            result.put(normalize(backend.name()), backend);
        }
        return Collections.unmodifiableMap(result);
    }

    private static String normalize(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("backend name cannot be blank");
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean matchesHost(InetSocketAddress address, String transferHost) {
        if (normalizeHost(address.getHostString()).equals(transferHost)) {
            return true;
        }
        InetAddress resolved = address.getAddress();
        return resolved != null && normalizeHost(resolved.getHostAddress()).equals(transferHost);
    }

    private static String normalizeHost(String host) {
        String normalized = host.trim();
        if (normalized.length() > 1 && normalized.charAt(0) == '['
                && normalized.charAt(normalized.length() - 1) == ']') {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.endsWith(".") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
