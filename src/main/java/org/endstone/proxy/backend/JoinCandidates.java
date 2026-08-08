package org.endstone.proxy.backend;

import org.endstone.proxy.config.BackendConfig;
import org.endstone.proxy.config.JoinConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Expands the join try-list into the flat sequence of attempts a joining player gets.
 *
 * <p>Pure, and separate from {@link BackendConnector}, because the ordering rules are where this
 * gets subtly wrong: the routed backend has to come first even when the try-list does not mention
 * it, a try-list that repeats it must not give it two turns, and "give up" has to mean nothing more
 * than "the list ran out".</p>
 */
final class JoinCandidates {
    private JoinCandidates() {
    }

    static List<BackendConfig> expand(
            BackendConfig routed,
            JoinConfig join,
            BackendDirectory backendDirectory
    ) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<BackendConfig> ordered = new ArrayList<>();
        // Where the player was actually routed always leads, whether by forced host or by default.
        // The try-list says where to go next, not where to start.
        ordered.add(routed);
        seen.add(normalize(routed.name()));

        for (String name : join.tryOrder()) {
            BackendConfig backend = backendDirectory.find(name).orElse(null);
            // An unknown name is a config typo and costs one candidate, never the session.
            if (backend != null && seen.add(normalize(backend.name()))) {
                ordered.add(backend);
            }
        }

        List<BackendConfig> candidates = new ArrayList<>();
        for (BackendConfig backend : ordered) {
            for (int attempt = 0; attempt < join.attemptsPerBackend(); attempt++) {
                candidates.add(backend);
            }
        }
        return List.copyOf(candidates);
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
