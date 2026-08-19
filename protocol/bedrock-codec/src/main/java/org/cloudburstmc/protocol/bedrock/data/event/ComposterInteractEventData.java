package org.cloudburstmc.protocol.bedrock.data.event;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.cloudburstmc.protocol.bedrock.data.BlockInteractionType;

/**
 * The interaction type is carried both as the resolved enum and as the raw wire ordinal.
 *
 * <p>Reading it as an enum alone is not enough for a proxy: the value is a plain index that the game
 * extends between versions, so an unknown one makes the whole event undecodable and, worse, a known
 * one that this build happens to number differently is re-encoded as a value the client rejects.
 * The raw ordinal is written back exactly as it arrived; {@link #getBlockInteractionType()} degrades
 * to {@link BlockInteractionType#NONE} for callers that switch on it. Same reasoning as
 * {@code DisconnectPacket.setReasonOrdinal}.
 */
@EqualsAndHashCode
@ToString
public class ComposterInteractEventData implements EventData {
    private final BlockInteractionType blockInteractionType;
    private final int rawBlockInteractionType;
    private final int itemId;

    public ComposterInteractEventData(BlockInteractionType blockInteractionType, int itemId) {
        this.blockInteractionType = blockInteractionType;
        this.rawBlockInteractionType = blockInteractionType.ordinal();
        this.itemId = itemId;
    }

    public ComposterInteractEventData(int rawBlockInteractionType, int itemId) {
        this.blockInteractionType = BlockInteractionType.byOrdinal(rawBlockInteractionType);
        this.rawBlockInteractionType = rawBlockInteractionType;
        this.itemId = itemId;
    }

    public BlockInteractionType getBlockInteractionType() {
        return this.blockInteractionType;
    }

    /**
     * The interaction type exactly as it appeared on the wire. Written back verbatim so a relayed
     * event stays byte-identical even when this build's enum does not know the value.
     */
    public int getRawBlockInteractionType() {
        return this.rawBlockInteractionType;
    }

    public int getItemId() {
        return this.itemId;
    }

    @Override
    public EventDataType getType() {
        return EventDataType.COMPOSTER_INTERACT;
    }
}
