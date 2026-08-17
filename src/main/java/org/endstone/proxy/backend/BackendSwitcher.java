package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.endstone.proxy.config.BackendConfig;
import org.endstone.proxy.config.BackendSwitchConfig;

import java.util.concurrent.TimeUnit;

/**
 * Moves a player to a backend and keeps trying for as long as the retry window allows.
 *
 * <p>Extracted from the {@code /server} handler because {@code /send} — and the console — move
 * players too, and a second copy of the retry-and-lock dance is a second place for it to go wrong.
 * Holds nothing per-connection, so one instance serves the whole proxy.</p>
 */
public final class BackendSwitcher {
    private final BackendConnector backendConnector;
    private final BackendSwitchConfig switchConfig;

    public BackendSwitcher(BackendConnector backendConnector, BackendSwitchConfig switchConfig) {
        this.backendConnector = backendConnector;
        this.switchConfig = switchConfig == null ? BackendSwitchConfig.defaults() : switchConfig;
    }

    /**
     * Starts a switch, reporting to the player as it goes.
     *
     * @return false when the switch could not be started at all — already there, or already
     *         switching — in which case the player has been told why
     */
    public boolean switchBackend(ProxyConnection connection, BackendConfig backend) {
        if (backend.name().equalsIgnoreCase(String.valueOf(connection.backendName()))) {
            sendMessage(connection, "You are already connected to " + backend.name() + ".");
            return false;
        }
        if (connection.beginBackendSwitch(backend.name()) != ProxyConnection.SwitchStart.STARTED) {
            sendMessage(connection, "Already connecting to " + connection.backendSwitchTarget() + ".");
            return false;
        }

        // Nothing is dialled for a reconnect — the client leaves and comes back on its own — so the
        // switch lock must be released here rather than by an attempt that never runs.
        if (backendConnector.needsReconnectToReach(connection, backend)) {
            connection.finishBackendSwitch();
            return backendConnector.reconnectTo(connection, backend);
        }

        sendMessage(connection, "Connecting to " + backend.name() + "...");
        // connectForSwitch calls awaitUninterruptibly() internally, which must not run
        // on a Netty I/O thread — doing so throws BlockingOperationException. Dispatch
        // to a daemon thread so the I/O loop is never blocked.
        Thread thread = new Thread(
                () -> attemptSwitch(connection, backend),
                "backend-switch-" + backend.name()
        );
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /**
     * Keeps retrying the same backend in the background until the retry window elapses.
     *
     * <p>The usual reason a switch fails is that the target is restarting, and a single attempt
     * against a server that comes back a few seconds later reports failure for good. Retries are
     * silent — the player asked to be moved, and a per-attempt commentary tells them nothing they
     * can act on. They hear one message if it eventually works and one if it does not.</p>
     *
     * <p>The switch lock is held across the whole window — a second {@code /server} in the middle of
     * it would leave two switches racing for the same connection — and released once, at the end.</p>
     */
    private void attemptSwitch(ProxyConnection connection, BackendConfig backend) {
        long startedAtNanos = System.nanoTime();
        long deadlineNanos = startedAtNanos + TimeUnit.MILLISECONDS.toNanos(switchConfig.retryWindowMillis());
        long retryDelayNanos = TimeUnit.MILLISECONDS.toNanos(switchConfig.retryDelayMillis());
        boolean switched = false;
        int attempts = 0;
        try {
            while (connection.client().isConnected()) {
                attempts++;
                if (BackendSwitchAttempt.run(backendConnector, connection, backend, switchConfig.timeoutMillis())) {
                    switched = true;
                    return;
                }
                // Include the pause in the check, so we never sleep only to give up on waking.
                if (System.nanoTime() + retryDelayNanos >= deadlineNanos) {
                    break;
                }
                if (!sleep(switchConfig.retryDelayMillis())) {
                    return;
                }
            }
            if (!connection.client().isConnected()) {
                return;
            }
            System.out.printf(
                    "Giving up on switching %s to backend %s after %d attempt(s) over %dms.%n",
                    connection.client().getSocketAddress(),
                    backend.name(),
                    attempts,
                    (System.nanoTime() - startedAtNanos) / 1_000_000L
            );
            sendMessage(connection, String.format(
                    "Could not connect to %s. You are still on %s.",
                    backend.name(),
                    connection.backendName()
            ));
        } finally {
            // On success the lock was already cleared by setBackend, and clearing it again could
            // stamp on a switch the player has started since.
            if (!switched) {
                connection.finishBackendSwitch();
            }
        }
    }

    private static boolean sleep(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static void sendMessage(ProxyConnection connection, String message) {
        if (connection.client() == null || !connection.client().isConnected()) {
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
