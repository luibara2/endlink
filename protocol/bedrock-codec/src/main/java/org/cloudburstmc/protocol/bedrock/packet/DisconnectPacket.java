package org.cloudburstmc.protocol.bedrock.packet;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.cloudburstmc.protocol.bedrock.data.DisconnectFailReason;
import org.cloudburstmc.protocol.common.PacketSignal;

@Data
@EqualsAndHashCode(doNotUseGetters = true)
@ToString(doNotUseGetters = true)
public class DisconnectPacket implements BedrockPacket {
    private DisconnectFailReason reason = DisconnectFailReason.UNKNOWN;
    /**
     * The reason ordinal exactly as it appeared on the wire, or -1 when this packet was built rather
     * than decoded.
     *
     * <p>Servers add new {@link DisconnectFailReason} values faster than this enum tracks them, and
     * indexing {@code values()} with an ordinal from a newer build throws — which turns a routine
     * "the server is shutting down" into an undecodable packet. A proxy has to relay a disconnect it
     * does not recognise, so the raw value is carried here and written back verbatim; {@link #reason}
     * degrades to {@link DisconnectFailReason#UNKNOWN} for callers that switch on it.</p>
     */
    private int rawReason = -1;
    private boolean messageSkipped;
    private CharSequence kickMessage;
    /**
     * @since v712
     */
    private CharSequence filteredMessage = "";

    @Override
    public final PacketSignal handle(BedrockPacketHandler handler) {
        return handler.handle(this);
    }

    public BedrockPacketType getPacketType() {
        return BedrockPacketType.DISCONNECT;
    }

    @Override
    public DisconnectPacket clone() {
        try {
            return (DisconnectPacket) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * The ordinal to put back on the wire: the decoded raw value when there was one, otherwise the
     * ordinal of {@link #reason}. Keeps a relayed disconnect byte-identical to the one received.
     */
    public int getReasonOrdinal() {
        return rawReason >= 0 ? rawReason : reason.ordinal();
    }

    /**
     * Records the reason ordinal read off the wire, resolving it to a known {@link
     * DisconnectFailReason} when this build has one and falling back to {@link
     * DisconnectFailReason#UNKNOWN} when it does not.
     */
    public void setReasonOrdinal(int ordinal) {
        this.rawReason = ordinal;
        DisconnectFailReason[] reasons = DisconnectFailReason.values();
        this.reason = ordinal >= 0 && ordinal < reasons.length ? reasons[ordinal] : DisconnectFailReason.UNKNOWN;
    }

    public String getKickMessage() {
        return getKickMessage(String.class);
    }

    public <T extends CharSequence> T getKickMessage(Class<T> type) {
        return type.cast(kickMessage);
    }

    public String getFilteredMessage() {
        return getFilteredMessage(String.class);
    }

    public <T extends CharSequence> T getFilteredMessage(Class<T> type) {
        return type.cast(filteredMessage);
    }
}

