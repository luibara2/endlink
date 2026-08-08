package org.endstone.proxy.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Routes a joining player to a backend based on the address they typed into their server list.
 *
 * <p>Configured as one line per hostname, so hostnames containing dots need no quoting:</p>
 *
 * <pre>
 * forcedHost.play.example.com=survival
 * forcedHost.creative.example.com=creative
 * </pre>
 *
 * <p><b>Not a security boundary.</b> The hostname arrives in the client's {@code ServerAddress}
 * claim, which is signed by the client's own key rather than Mojang's — anyone can edit it and
 * arrive claiming any hostname. Forced hosts decide which door a player walks through by default;
 * whether they are allowed through it is {@link PermissionsConfig}'s job, and a backend that must
 * stay staff-only needs to enforce that itself.</p>
 *
 * <p>An unknown hostname is not an error: the player goes to the default backend, exactly as if no
 * forced host were configured. That way a new DNS name pointed at the proxy before its config entry
 * exists still lets people in.</p>
 */
public record ForcedHostsConfig(Map<String, String> byHostname) {
    public ForcedHostsConfig {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (byHostname != null) {
            for (Map.Entry<String, String> entry : byHostname.entrySet()) {
                String hostname = normalizeHostname(entry.getKey());
                String backend = ConfigValues.normalize(entry.getValue());
                if (!hostname.isEmpty() && !backend.isEmpty()) {
                    normalized.put(hostname, backend);
                }
            }
        }
        byHostname = Map.copyOf(normalized);
    }

    public static ForcedHostsConfig empty() {
        return new ForcedHostsConfig(Map.of());
    }

    public static ForcedHostsConfig from(Properties properties) {
        Map<String, String> byHostname = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.length() > "forcedHost.".length() && key.startsWith("forcedHost.")) {
                byHostname.put(
                        key.substring("forcedHost.".length()),
                        ConfigValues.stripInlineComment(properties.getProperty(key, ""))
                );
            }
        }
        return new ForcedHostsConfig(byHostname);
    }

    public boolean isEmpty() {
        return byHostname.isEmpty();
    }

    /**
     * The backend name configured for the address a client connected with, if any.
     *
     * @param serverAddress the raw {@code ServerAddress} claim, which may carry a port
     *                      ({@code play.example.com:19132}) and may be null or junk
     */
    public Optional<String> backendFor(String serverAddress) {
        String hostname = normalizeHostname(hostPart(serverAddress));
        if (hostname.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byHostname.get(hostname));
    }

    /** Strips the port, leaving an IPv6 literal's colons alone. */
    private static String hostPart(String serverAddress) {
        if (serverAddress == null) {
            return "";
        }
        String address = serverAddress.trim();
        if (address.startsWith("[")) {
            int end = address.indexOf(']');
            return end < 0 ? address : address.substring(0, end + 1);
        }
        int colon = address.indexOf(':');
        // More than one colon and no brackets means a bare IPv6 literal, which has no port to strip.
        if (colon >= 0 && address.indexOf(':', colon + 1) < 0) {
            return address.substring(0, colon);
        }
        return address;
    }

    private static String normalizeHostname(String hostname) {
        if (hostname == null) {
            return "";
        }
        // A fully-qualified name may arrive with the root dot; "PLAY.Example.com." is the same host.
        String normalized = hostname.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
