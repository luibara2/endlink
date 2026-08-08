package org.endstone.proxy.diagnostics;

/**
 * One protocol-level fault: the proxy and a backend disagreed about the wire, and a player paid.
 *
 * <p>Kept distinct from a backend simply going away, because the two want opposite handling. A
 * backend that is down is a transient infrastructure problem and moving the player to a fallback is
 * the kind thing to do. A protocol fault is a <em>bug</em> — the fallback will not fix it, the player
 * usually bounces straight back, and the failover hides the evidence. Those get disconnected with a
 * reason and written to {@link ProtocolFaultLog}.</p>
 */
public record ProtocolFault(String backendName, String playerName, String detail) {

    /**
     * A fault built from a {@code PacketViolationWarningPacket}, which is the authoritative case:
     * the backend is telling us in so many words that it could not read something we sent.
     */
    public static ProtocolFault fromViolation(String backendName, String playerName, PacketViolation violation) {
        return new ProtocolFault(backendName, playerName, violation.toString());
    }

    /** One self-contained line: everything needed to act on this without the relay log. */
    public String describe() {
        return "backend=" + this.backendName + " player=" + this.playerName + " " + this.detail;
    }
}
