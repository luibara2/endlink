package org.endstone.proxy.backend;

import org.endstone.proxy.config.BackendConfig;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs one backend switch to completion and reports whether the player actually arrived.
 *
 * <p>Shared by {@code /server} and by failover so the two cannot drift on cleanup. The outcome of a
 * switch is mostly asynchronous — success arrives with the target's StartGame on its event loop —
 * so this waits for it rather than assuming the dial-out result is the answer.</p>
 *
 * <p>Deliberately does <em>not</em> touch the connection's switch lock. Whether a failed attempt
 * ends the switch or is followed by another one is the caller's decision: {@code /server} retries
 * the same backend and must keep the lock across all of them, while failover releases it between
 * candidates.</p>
 */
public final class BackendSwitchAttempt {
    /** How often the wait wakes up to notice that the player has quit. */
    private static final long POLL_MILLIS = 250;

    private BackendSwitchAttempt() {
    }

    public static boolean run(
            BackendConnector connector,
            ProxyConnection connection,
            BackendConfig target,
            long timeoutMillis
    ) {
        CompletableFuture<Void> switched = connector.connectForSwitch(connection, target);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            try {
                switched.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
                return true;
            } catch (TimeoutException exception) {
                // A client that quits mid-attempt closes the pending backend out from under the
                // switch, which then never reports an outcome. Poll for it rather than holding this
                // thread and a half-open backend session for the full timeout.
                if (!connection.client().isConnected()) {
                    return abandon(connection, target, "the player disconnected");
                }
                if (System.nanoTime() >= deadline) {
                    return abandon(
                            connection,
                            target,
                            "it did not finish its handshake within " + timeoutMillis + "ms"
                    );
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return abandon(connection, target, "the switch was interrupted");
            } catch (Exception exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                return abandon(connection, target, String.valueOf(cause));
            }
        }
    }

    /**
     * Tears down a failed attempt.
     *
     * <p>Always called on failure, not only on timeout. A target that is simply not listening never
     * gets far enough for RakNet to create a session, and the cleanup inside {@code connectForSwitch}
     * hangs off that session — so the most common failure of all, "the backend is down", is the one
     * that skips it.</p>
     *
     * @return false, so callers can {@code return abandon(...)}
     */
    private static boolean abandon(ProxyConnection connection, BackendConfig target, String why) {
        System.out.printf("Abandoning switch to backend %s: %s.%n", target.name(), why);
        BackendSession pending = connection.pendingBackend();
        if (pending != null) {
            connection.clearPendingBackend(pending);
            if (pending.isConnected()) {
                pending.setDisconnectClientOnClose(false);
                pending.discardInboundPackets();
                pending.disconnect("Switch to " + target.name() + " abandoned");
            }
        }
        return false;
    }
}
