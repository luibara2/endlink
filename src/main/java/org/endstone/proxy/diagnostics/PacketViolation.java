package org.endstone.proxy.diagnostics;

import io.netty.buffer.ByteBuf;

/**
 * A {@code PacketViolationWarningPacket} the backend sent us, decoded by hand.
 *
 * <p>This has to be done by hand because CloudburstMC marks packet 156 SERVER-bound, so a copy
 * arriving on the client leg fails {@code tryDecode}'s recipient check and is raw-forwarded as an
 * {@code UnknownPacket} — the proxy never gets a typed one. Which is unfortunate, because it is the
 * single most informative packet BDS sends: it names the packet the proxy got wrong, the schema
 * member it got wrong, and whether the connection is about to be torn down over it.</p>
 *
 * <p>Four fields, three of them zigzag varints:</p>
 * <pre>
 *   varint  Type            0 = malformed packet
 *   varint  Severity        0 = warning, 1 = final warning, 2 = terminating connection
 *   varint  Cause packet id the packet id BDS could not read
 *   string  Message         BDS's own reader error
 * </pre>
 */
public record PacketViolation(int type, int severity, int causePacketId, String message) {

    public static final int PACKET_ID = 156;

    public static final int SEVERITY_WARNING = 0;
    public static final int SEVERITY_FINAL_WARNING = 1;
    public static final int SEVERITY_TERMINATING = 2;

    /** Only a terminating violation is fatal; the softer two are BDS complaining but carrying on. */
    public boolean isTerminating() {
        return this.severity >= SEVERITY_TERMINATING;
    }

    public String severityName() {
        return switch (this.severity) {
            case SEVERITY_WARNING -> "warning";
            case SEVERITY_FINAL_WARNING -> "final warning";
            case SEVERITY_TERMINATING -> "terminating connection";
            default -> "severity " + this.severity;
        };
    }

    @Override
    public String toString() {
        return "packet " + this.causePacketId + " rejected (" + severityName() + "): " + this.message;
    }

    /**
     * Decodes {@code payload} without consuming it, or null when the bytes are not a violation this
     * understands. Never throws: a malformed diagnostic must not become a second fault.
     */
    public static PacketViolation decode(ByteBuf payload) {
        if (payload == null) {
            return null;
        }
        ByteBuf view = payload.duplicate();
        try {
            int type = readZigzag(view);
            int severity = readZigzag(view);
            int causePacketId = readZigzag(view);
            int length = readUnsignedVarint(view);
            if (length < 0 || length > view.readableBytes()) {
                return null;
            }
            String message = view.readCharSequence(length, java.nio.charset.StandardCharsets.UTF_8).toString();
            return new PacketViolation(type, severity, causePacketId, message);
        } catch (RuntimeException notAViolation) {
            return null;
        }
    }

    private static int readZigzag(ByteBuf buffer) {
        int raw = readUnsignedVarint(buffer);
        return (raw >>> 1) ^ -(raw & 1);
    }

    private static int readUnsignedVarint(ByteBuf buffer) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = buffer.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("varint too long");
    }
}
