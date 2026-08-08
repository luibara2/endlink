package org.endstone.proxy.config;

import java.util.Locale;

/**
 * What to do with a player when the backend they are on kicks them.
 *
 * <p>Two very different events arrive as the same packet. A backend shutting down kicks everyone
 * before the socket closes, and those players should be moved to a fallback. A backend banning
 * somebody also kicks them, and moving <em>that</em> player to a fallback overrides the ban — and
 * loops, because the fallback transfers them straight back to the backend that just refused them.</p>
 *
 * <p>The wire distinguishes them, which is what {@link #AUTO} keys off. A {@code DisconnectPacket}
 * carries a {@code messageSkipped} flag: a host-level disconnect sends only a reason
 * ({@code HOST_DISCONNECTED}, {@code SERVER_SHUTDOWN}) with the message skipped, while a ban or a
 * moderator kick carries text written for that specific player. Message present means somebody
 * decided something about this player; message absent means the host went away.</p>
 */
public enum BackendKickAction {

    /** Fail over when the backend skipped the message, pass the kick through when it sent one. */
    AUTO,
    /** Never fail over on a kick. Bans always hold; a restart drops its players. */
    DISCONNECT,
    /** Always fail over on a kick. Restarts are seamless; a ban can be escaped. */
    FAILOVER;

    /**
     * Decides for one kick.
     *
     * @param backendSuppliedMessage whether the backend sent kick text of its own
     */
    public boolean failsOver(boolean backendSuppliedMessage) {
        return switch (this) {
            case AUTO -> !backendSuppliedMessage;
            case DISCONNECT -> false;
            case FAILOVER -> true;
        };
    }

    /** Unrecognised values fall back to {@link #AUTO} rather than refusing to start the proxy. */
    public static BackendKickAction parse(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (BackendKickAction candidate : values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        System.err.printf(
                "Unknown failover.onBackendKick '%s'; using auto. Valid values: auto, disconnect, failover.%n",
                value);
        return AUTO;
    }
}
