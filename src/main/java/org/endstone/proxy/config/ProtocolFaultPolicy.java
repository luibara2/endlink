package org.endstone.proxy.config;

/**
 * What to do with a player whose session hit a protocol fault rather than a backend outage.
 *
 * <p>Failover exists for a backend that has gone away: the player is moved somewhere else and
 * carries on. A protocol fault is a different animal — the proxy and the backend disagreed about the
 * wire — and failing that player over does three unhelpful things. It does not fix anything, because
 * the bug travels with them. It usually bounces them straight back, because the fallback hands them
 * to the same backend again. And it buries the evidence in a normal-looking failover line, which is
 * how a codec bug can run for days looking like flaky hosting.</p>
 *
 * <p>So by default a protocol fault disconnects the player with a reason and is written to a
 * dedicated log. Set {@code protocolFault.action=failover} to get the old behaviour back.</p>
 */
public record ProtocolFaultPolicy(Action action, String message, String logFile) {

    public enum Action {
        /** Kick the player with {@link #message} and log the fault. The default. */
        DISCONNECT,
        /** Treat it as an ordinary backend loss and walk the fallback chain. Still logged. */
        FAILOVER
    }

    public static final String DEFAULT_MESSAGE =
            "Disconnected: the server sent something this proxy could not relay. "
                    + "This has been logged - please report it.";

    public static final String DEFAULT_LOG_FILE = "logs/protocol-errors.log";

    public ProtocolFaultPolicy {
        if (action == null) {
            action = Action.DISCONNECT;
        }
        if (message == null || message.isBlank()) {
            message = DEFAULT_MESSAGE;
        }
        if (logFile != null) {
            logFile = logFile.trim();
        }
    }

    public static ProtocolFaultPolicy defaults() {
        return new ProtocolFaultPolicy(Action.DISCONNECT, DEFAULT_MESSAGE, DEFAULT_LOG_FILE);
    }

    public boolean disconnects() {
        return this.action == Action.DISCONNECT;
    }

    /** An empty {@code protocolFault.logFile} turns the dedicated log off without disabling the rule. */
    public boolean logsToFile() {
        return this.logFile != null && !this.logFile.isEmpty();
    }

    /** Unrecognised values fall back to the default rather than refusing to start the proxy. */
    public static Action parseAction(String value) {
        if (value == null) {
            return Action.DISCONNECT;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        for (Action candidate : Action.values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        System.err.printf(
                "Unknown protocolFault.action '%s'; using %s. Valid values: disconnect, failover.%n",
                value, Action.DISCONNECT.name().toLowerCase(java.util.Locale.ROOT));
        return Action.DISCONNECT;
    }
}
