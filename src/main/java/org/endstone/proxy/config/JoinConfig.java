package org.endstone.proxy.config;

import java.util.List;
import java.util.Properties;

/**
 * The ordered list of backends to try when a player first joins the proxy, Velocity's {@code try}.
 *
 * <p>Without it a player whose backend is already down is simply kicked — mid-session failover only
 * covers a backend that dies <em>under</em> someone, because it works by moving a client that is
 * already in a world.</p>
 *
 * @param tryOrder            backend names, in order. Empty means "just the one backend the player
 *                            was routed to", which is the behaviour this existed to replace
 * @param attemptsPerBackend  how many times to try each candidate before moving to the next. A
 *                            backend that is <em>down</em> fails fast, but one that is
 *                            <em>restarting</em> may refuse a connection a second before it would
 *                            have accepted it
 */
public record JoinConfig(List<String> tryOrder, int attemptsPerBackend) {
    public JoinConfig {
        if (tryOrder == null) {
            throw new IllegalArgumentException("tryOrder cannot be null");
        }
        if (attemptsPerBackend < 1) {
            throw new IllegalArgumentException("attemptsPerBackend must be positive");
        }
        tryOrder = List.copyOf(tryOrder);
    }

    public static JoinConfig defaults() {
        return new JoinConfig(List.of(), 1);
    }

    /**
     * Reads {@code join.try}, defaulting to the failover chain.
     *
     * <p>Sharing the default is deliberate: "where does a player go when a backend is not available"
     * is one question, and an operator who has already answered it for a backend dying should not
     * have to answer it again for a backend that was never up.</p>
     */
    public static JoinConfig from(Properties properties, FailoverConfig failover) {
        List<String> tryOrder = properties.containsKey("join.try")
                ? ConfigValues.commaList(properties.getProperty("join.try"), "join.try")
                : failover.fallbacks();
        String attempts = properties.getProperty("join.attemptsPerBackend");
        return new JoinConfig(
                tryOrder,
                attempts == null || attempts.isBlank()
                        ? 1
                        : Math.max(1, Integer.parseInt(ConfigValues.stripInlineComment(attempts)))
        );
    }
}
