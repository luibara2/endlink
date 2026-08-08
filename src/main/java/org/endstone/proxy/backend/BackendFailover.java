package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.endstone.proxy.config.BackendConfig;
import org.endstone.proxy.config.FailoverConfig;
import org.endstone.proxy.config.ProtocolFaultPolicy;
import org.endstone.proxy.diagnostics.ProtocolFault;
import org.endstone.proxy.diagnostics.ProtocolFaultLog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Velocity-style failover: when the backend a player is on goes away, walk their configured
 * fallback chain and move them to the first backend that accepts them, instead of dropping them
 * off the proxy.
 *
 * <p>This deliberately reuses the ordinary backend-switch path, so an unexpected backend loss looks
 * to the client exactly like {@code /hub} — a loading screen and then the fallback world. Nothing
 * here re-implements the switch; it only decides <em>where</em> and drives the retries.</p>
 */
public final class BackendFailover {
    /**
     * How long a single fallback attempt may take before it is abandoned and the next candidate is
     * tried. Covers the whole handshake up to the fallback's StartGame, not just the RakNet connect,
     * so it has to tolerate a backend that is slow rather than dead.
     */
    private static final long ATTEMPT_TIMEOUT_MILLIS = 20_000;

    private final BackendDirectory backendDirectory;
    private final BackendConnector backendConnector;
    private final FailoverConfig failoverConfig;

    public BackendFailover(
            BackendDirectory backendDirectory,
            BackendConnector backendConnector,
            FailoverConfig failoverConfig
    ) {
        this.backendDirectory = backendDirectory;
        this.backendConnector = backendConnector;
        this.failoverConfig = failoverConfig == null ? FailoverConfig.disabled() : failoverConfig;
        warnAboutUnknownTargets();
    }

    /**
     * Whether a backend that kicks a player should be treated as an outage to rescue them from.
     *
     * <p>Under the default {@code auto} policy this is decided per kick, by whether the backend
     * bothered to write a message. A host-level disconnect sends a bare reason and its players
     * should be rescued; a ban carries text meant for that one player and moving them would both
     * override the ban and loop them back into it.</p>
     */
    public boolean failsOverOnBackendKick(boolean backendSuppliedMessage) {
        return this.failoverConfig.onBackendKick().failsOver(backendSuppliedMessage);
    }

    /** Opened lazily so a proxy that never sees a fault never creates the file. */
    private synchronized ProtocolFaultLog protocolFaultLog() {
        if (this.protocolFaultLog == null) {
            ProtocolFaultPolicy policy = this.failoverConfig.protocolFault();
            this.protocolFaultLog = policy.logsToFile()
                    ? new ProtocolFaultLog(java.nio.file.Path.of(policy.logFile()))
                    : ProtocolFaultLog.disabled();
        }
        return this.protocolFaultLog;
    }

    private ProtocolFaultLog protocolFaultLog;

    /**
     * Resolves the ordered fallback backends for a player who has just lost {@code lostBackendName}.
     * Names that are not configured backends are skipped rather than failing the whole chain, so one
     * typo in the config cannot cost a player their session.
     */
    public static List<BackendConfig> targets(
            FailoverConfig failoverConfig,
            BackendDirectory backendDirectory,
            String lostBackendName
    ) {
        List<BackendConfig> targets = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : failoverConfig.fallbacksFor(lostBackendName)) {
            BackendConfig backend = backendDirectory.find(name).orElse(null);
            if (backend != null && seen.add(backend.name().toLowerCase(Locale.ROOT))) {
                targets.add(backend);
            }
        }
        return List.copyOf(targets);
    }

    /**
     * Takes over an unexpected backend loss, if failover applies to it.
     *
     * @return true if the caller must leave the client connected because a failover is now running;
     *         false if the caller should disconnect the client as it would have before
     */
    public boolean begin(ProxyConnection connection, String lostBackendName, CharSequence reason) {
        return begin(connection, lostBackendName, reason, null);
    }

    /**
     * As {@link #begin(ProxyConnection, String, CharSequence)}, but told why the backend was lost.
     *
     * <p>When {@code fault} is non-null the session did not end because the backend went away — it
     * ended because the proxy and the backend disagreed about the wire. Moving that player to a
     * fallback fixes nothing, usually bounces them straight back, and makes a codec bug look like
     * flaky hosting in the log. Under the default policy they are disconnected with a reason and the
     * fault is written to its own file instead. See {@link ProtocolFaultPolicy}.</p>
     *
     * @return true when this method has taken responsibility for the client — either a failover is
     *         running or the player has been disconnected with a specific reason
     */
    public boolean begin(
            ProxyConnection connection,
            String lostBackendName,
            CharSequence reason,
            ProtocolFault fault
    ) {
        if (fault != null) {
            protocolFaultLog().record(fault);
            ProtocolFaultPolicy policy = failoverConfig.protocolFault();
            if (policy.disconnects()) {
                System.err.printf(
                        "Protocol fault on backend %s for %s: %s. Disconnecting rather than failing over%s.%n",
                        lostBackendName,
                        connection.client().getSocketAddress(),
                        fault.detail(),
                        policy.logsToFile() ? " (logged to " + policy.logFile() + ")" : ""
                );
                if (connection.client().isConnected()) {
                    connection.client().disconnect(policy.message());
                }
                return true;
            }
        }
        if (!failoverConfig.enabled() || !connection.client().isConnected()) {
            return false;
        }
        if (!connection.hasClientJoinedWorld()) {
            // The client has no world to be moved out of yet; a switch cannot represent this.
            return false;
        }
        if (connection.isSwitchingBackend() || connection.pendingBackend() != null) {
            // A switch is already in flight and will replace this backend on its own. Checked
            // before the pending session exists too: /server marks the switch as begun and only
            // then dials out, and a backend that dies inside that window would otherwise start a
            // failover that immediately loses the race for the switch lock.
            return false;
        }
        List<BackendConfig> targets = targets(failoverConfig, backendDirectory, lostBackendName);
        if (targets.isEmpty()) {
            System.out.printf(
                    "No failover target configured for backend %s; disconnecting %s.%n",
                    lostBackendName,
                    connection.client().getSocketAddress()
            );
            return false;
        }
        ProxyConnection.FailoverStart start = connection.beginFailover();
        if (start != ProxyConnection.FailoverStart.STARTED) {
            System.out.printf(
                    "Not failing %s over from backend %s: %s.%n",
                    connection.client().getSocketAddress(),
                    lostBackendName,
                    start == ProxyConnection.FailoverStart.TOO_MANY
                            ? "too many failovers in a row, the fallbacks are dropping the player as fast as they arrive"
                            : "a failover is already running"
            );
            return false;
        }

        // A switch reset still driving the backend that just died must not complete: it would hand
        // its post-switch initialization token to a dead session and strand the player on whichever
        // fallback takes them.
        BackendSwitchReset staleReset = connection.backendSwitchReset();
        if (staleReset != null) {
            staleReset.abandon(connection);
        }

        // connectForSwitch blocks on the backend connect, which must not happen on a Netty I/O
        // thread — and this runs on one, from the dead backend's disconnect callback.
        Thread thread = new Thread(
                () -> run(connection, lostBackendName, reason, targets),
                "backend-failover-" + lostBackendName
        );
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void run(
            ProxyConnection connection,
            String lostBackendName,
            CharSequence reason,
            List<BackendConfig> targets
    ) {
        try {
            System.out.printf(
                    "Backend %s died under %s (%s); failing over through %s.%n",
                    lostBackendName,
                    connection.client().getSocketAddress(),
                    reason,
                    targets.stream().map(BackendConfig::name).toList()
            );
            sendMessage(connection, "Lost connection to " + lostBackendName + ".");
            for (BackendConfig target : targets) {
                if (!connection.client().isConnected()) {
                    return;
                }
                sendMessage(connection, "Moving you to " + target.name() + "...");
                if (attempt(connection, target)) {
                    System.out.printf(
                            "Failed over %s from backend %s to %s.%n",
                            connection.client().getSocketAddress(),
                            lostBackendName,
                            target.name()
                    );
                    return;
                }
            }
            System.out.printf(
                    "Failover exhausted for %s after losing backend %s; no fallback of %s accepted the player.%n",
                    connection.client().getSocketAddress(),
                    lostBackendName,
                    targets.stream().map(BackendConfig::name).toList()
            );
            if (connection.client().isConnected()) {
                connection.client().disconnect(reason);
            }
        } catch (Exception exception) {
            System.err.printf(
                    "Failover for %s after losing backend %s failed unexpectedly: %s.%n",
                    connection.client().getSocketAddress(),
                    lostBackendName,
                    exception
            );
            if (connection.client().isConnected()) {
                connection.client().disconnect(reason);
            }
        } finally {
            connection.finishFailover();
        }
    }

    /**
     * Tries one candidate. The switch lock is taken and released per candidate rather than held for
     * the whole chain, because each candidate is a switch to a different backend.
     */
    private boolean attempt(ProxyConnection connection, BackendConfig target) {
        if (connection.beginBackendSwitch(target.name()) != ProxyConnection.SwitchStart.STARTED) {
            System.out.printf(
                    "Skipping failover target %s: a switch to %s is already in progress.%n",
                    target.name(),
                    connection.backendSwitchTarget()
            );
            return false;
        }
        if (BackendSwitchAttempt.run(backendConnector, connection, target, ATTEMPT_TIMEOUT_MILLIS)) {
            return true;
        }
        connection.finishBackendSwitch();
        return false;
    }

    private void warnAboutUnknownTargets() {
        if (!failoverConfig.enabled()) {
            System.out.println("Backend failover is disabled; players are disconnected when their backend dies.");
            return;
        }
        Set<String> configured = new LinkedHashSet<>(failoverConfig.fallbacks());
        failoverConfig.backendFallbacks().values().forEach(configured::addAll);
        for (String name : configured) {
            if (backendDirectory.find(name).isEmpty()) {
                System.out.printf(
                        "WARNING: failover target '%s' is not a configured backend and will be skipped.%n",
                        name
                );
            }
        }
    }

    private static void sendMessage(ProxyConnection connection, String message) {
        if (!connection.client().isConnected()) {
            return;
        }
        TextPacket packet = new TextPacket();
        packet.setType(TextPacket.Type.SYSTEM);
        packet.setNeedsTranslation(false);
        packet.setSourceName("");
        packet.setMessage(message);
        packet.setXuid("");
        connection.client().sendPacket(packet);
    }
}
