package org.cloudburstmc.protocol.bedrock.packet;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.cloudburstmc.protocol.bedrock.data.event.EventData;
import org.cloudburstmc.protocol.common.PacketSignal;

@Data
@EqualsAndHashCode(doNotUseGetters = true)
@ToString(doNotUseGetters = true)
public class EventPacket implements BedrockPacket {
    private long uniqueEntityId;
    private boolean usePlayerId;
    private EventData eventData;
    /**
     * The {@code oneOf} payload discriminator that 1.21.130+ writes between the header and the event
     * body.
     *
     * <p>Upstream models this as {@code EventData.getPayloadType()}, defaulting to -1 and throwing on
     * any type that has not been given a real value. A proxy cannot do that: it has to relay every
     * event a backend sends, including ones whose payload type it has no opinion about. Carrying the
     * value read off the wire here lets the field round-trip exactly, whatever the event type.
     *
     * <p>Omitting it entirely — as this vendored copy previously did on both the read and write side —
     * is self-consistent but does not match the wire: the event body is then parsed one varint late,
     * and the relayed copy reaches the client a byte short.
     */
    private int payloadType;

    @Override
    public final PacketSignal handle(BedrockPacketHandler handler) {
        return handler.handle(this);
    }

    public BedrockPacketType getPacketType() {
        return BedrockPacketType.EVENT;
    }

    public enum Event {
        ACHIEVEMENT_AWARDED,
        ENTITY_INTERACT,
        PORTAL_BUILT,
        PORTAL_USED,
        MOB_KILLED,
        CAULDRON_USED,
        PLAYER_DEATH,
        BOSS_KILLED,
        /**
         * @deprecated use {@link AgentActionEventPacket}
         */
        @Deprecated
        AGENT_COMMAND,
        AGENT_CREATED,
        PATTERN_REMOVED,
        SLASH_COMMAND_EXECUTED,
        FISH_BUCKETED,
        MOB_BORN,
        PET_DIED,
        CAULDRON_BLOCK_USED,
        COMPOSTER_BLOCK_USED,
        BELL_BLOCK_USED
    }

    @Override
    public EventPacket clone() {
        try {
            return (EventPacket) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }
}

