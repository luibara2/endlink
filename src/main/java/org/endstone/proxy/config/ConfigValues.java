package org.endstone.proxy.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Shared parsing for {@code key=a,b,c} settings.
 *
 * <p>{@code java.util.Properties} only treats {@code #} as a comment at the <em>start</em> of a
 * line, so {@code failover.fallbacks=lobby  # try list} parses as one backend literally named
 * "lobby  # try list". That has already cost a player their session once, so every comma-list
 * setting strips a trailing comment and says so rather than matching nothing in silence.
 */
final class ConfigValues {
    private ConfigValues() {
    }

    /**
     * Splits a comma-separated list, lower-casing and de-duplicating while preserving order. A blank
     * value yields an empty list, which callers may treat as meaningful rather than absent.
     *
     * @param setting the property name, used only in the inline-comment warning
     */
    static List<String> commaList(String value, String setting) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String uncommented = stripInlineComment(value);
        if (!uncommented.equals(value.trim())) {
            System.out.printf(
                    "WARNING: ignoring the inline comment in %s ('%s'). Properties files only treat '#'"
                            + " as a comment at the start of a line, so other settings in this file will"
                            + " keep theirs as part of the value.%n",
                    setting,
                    value.trim()
            );
        }
        return normalizedList(List.of(uncommented.split(",")));
    }

    static String stripInlineComment(String value) {
        int comment = value.length();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '#' || character == '!' || character == ';') {
                comment = index;
                break;
            }
        }
        return value.substring(0, comment).trim();
    }

    /** Order is meaningful (it is the try order); a name repeated in the config is not a second try. */
    static List<String> normalizedList(List<String> raw) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String name : raw) {
            if (name != null && !name.isBlank()) {
                unique.add(normalize(name));
            }
        }
        return List.copyOf(new ArrayList<>(unique));
    }

    static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
