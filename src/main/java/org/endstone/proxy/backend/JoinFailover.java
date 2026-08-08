package org.endstone.proxy.backend;

import org.endstone.proxy.config.BackendConfig;

/**
 * Walks the join try-list when the backend a player is being connected to will not have them.
 *
 * <p>Distinct from {@link BackendFailover}, which moves a client that is already in a world. Before
 * StartGame there is nothing to move — the client has never been given a world — so the only thing
 * that can be done is to start the connection again against the next candidate. That is why the
 * switch path cannot be reused here.</p>
 *
 * <p>A single dead backend is reported through several paths at once (the activation, the session
 * close, the relay's disconnect, and the rethrow the login handler catches). All of them route here,
 * and {@link ProxyConnection#claimJoinFailure()} makes sure exactly one of them advances the
 * sequence.</p>
 */
final class JoinFailover {
    private final BackendConnector backendConnector;

    JoinFailover(BackendConnector backendConnector) {
        this.backendConnector = backendConnector;
    }

    /**
     * @return true when the caller must <em>not</em> disconnect the client: either the next
     *         candidate is being tried, or the player has already been told the network is down
     */
    boolean handleJoinFailure(ProxyConnection connection, String failedBackendName, CharSequence reason) {
        if (!connection.isJoinSequenceActive() || connection.hasClientJoinedWorld()) {
            // Not a join failure. A backend dying under a player who is already in a world belongs
            // to BackendFailover, and the caller's own handling is correct.
            return false;
        }
        if (!connection.client().isConnected()) {
            connection.endJoinSequence();
            return true;
        }
        if (!connection.claimJoinFailure()) {
            // Another path got here first for this same attempt.
            return true;
        }

        BackendConfig next = connection.nextJoinCandidate();
        if (next == null) {
            connection.endJoinSequence();
            System.out.printf(
                    "No backend accepted %s at join; last was %s (%s).%n",
                    connection.clientLogin().authData().displayName(),
                    failedBackendName,
                    reason
            );
            connection.client().disconnect("All servers are offline. Please try again shortly.");
            return true;
        }

        System.out.printf(
                "Backend %s would not take %s at join (%s); trying %s.%n",
                failedBackendName,
                connection.clientLogin().authData().displayName(),
                reason,
                next.name()
        );
        // connectInternal blocks on awaitUninterruptibly, which must not run on a Netty I/O thread —
        // and every path that reaches here is on one.
        Thread thread = new Thread(
                () -> attempt(connection, next),
                "join-failover-" + next.name()
        );
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void attempt(ProxyConnection connection, BackendConfig backend) {
        try {
            backendConnector.connect(connection, backend);
        } catch (Exception exception) {
            // connect() reports through the activation, which comes back here; this only covers a
            // throw that never reached it. Without it a candidate that fails synchronously would
            // strand the player on a connection nobody is driving.
            handleJoinFailure(connection, backend.name(), String.valueOf(exception.getMessage()));
        }
    }
}
