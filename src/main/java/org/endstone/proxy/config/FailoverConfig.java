package org.endstone.proxy.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a player is sent when the backend they are on goes away.
 *
 * <p>Velocity-style: an ordered global try-list, overridable per backend. A per-backend entry always
 * wins over the global list, <em>including when it is configured empty</em> — that means "never fail
 * over from this backend, disconnect the player instead". A backend with no entry of its own uses
 * the global list.</p>
 */
public record FailoverConfig(
        boolean enabled,
        List<String> fallbacks,
        Map<String, List<String>> backendFallbacks,
        ProtocolFaultPolicy protocolFault,
        BackendKickAction onBackendKick
) {
    /** Keeps the many callers that predate the later components on their defaults. */
    public FailoverConfig(boolean enabled, List<String> fallbacks, Map<String, List<String>> backendFallbacks) {
        this(enabled, fallbacks, backendFallbacks, ProtocolFaultPolicy.defaults(), BackendKickAction.AUTO);
    }

    public FailoverConfig {
        if (protocolFault == null) {
            protocolFault = ProtocolFaultPolicy.defaults();
        }
        if (onBackendKick == null) {
            onBackendKick = BackendKickAction.AUTO;
        }
        if (fallbacks == null) {
            throw new IllegalArgumentException("fallbacks cannot be null");
        }
        if (backendFallbacks == null) {
            throw new IllegalArgumentException("backendFallbacks cannot be null");
        }
        fallbacks = names(fallbacks);
        Map<String, List<String>> normalizedOverrides = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : backendFallbacks.entrySet()) {
            normalizedOverrides.put(normalize(entry.getKey()), names(entry.getValue()));
        }
        backendFallbacks = Collections.unmodifiableMap(normalizedOverrides);
    }

    public static FailoverConfig disabled() {
        return new FailoverConfig(false, List.of(), Map.of(),
                ProtocolFaultPolicy.defaults(), BackendKickAction.AUTO);
    }

    /**
     * The ordered backend names to try for a player who has just lost {@code backendName}, already
     * filtered so a player is never sent straight back to the backend that just died.
     */
    public List<String> fallbacksFor(String backendName) {
        if (!enabled) {
            return List.of();
        }
        String lost = normalize(backendName);
        List<String> chain = backendFallbacks.getOrDefault(lost, fallbacks);
        return chain.stream()
                .filter(name -> !name.equals(lost))
                .toList();
    }

    /**
     * Splits a comma-separated backend list. An empty or blank value yields an empty list, which is
     * meaningful for a per-backend override: it disables failover from that backend.
     *
     * <p>A trailing {@code # comment} is stripped even though {@code java.util.Properties} does not
     * treat one as a comment — it only honours {@code #} at the start of a line, so
     * {@code failover.fallbacks=lobby  # try list} otherwise parses as a single backend named
     * "lobby  # try list", matches nothing, and silently costs a player their session. Backend names
     * can contain neither {@code #} nor whitespace, so this is unambiguous rather than lenient.</p>
     */
    public static List<String> parseList(String value) {
        return ConfigValues.commaList(value, "a failover list");
    }

    private static List<String> names(List<String> raw) {
        return ConfigValues.normalizedList(raw);
    }

    private static String normalize(String name) {
        return ConfigValues.normalize(name);
    }
}
