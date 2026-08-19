package org.cloudburstmc.protocol.bedrock.data.event;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.cloudburstmc.protocol.bedrock.data.BlockInteractionType;

/**
 * @see ComposterInteractEventData for why the raw ordinal is carried alongside the enum.
 */
@EqualsAndHashCode
@ToString
public class CauldronInteractEventData implements EventData {
    private final BlockInteractionType blockInteractionType;
    private final int rawBlockInteractionType;
    private final int itemId;

    public CauldronInteractEventData(BlockInteractionType blockInteractionType, int itemId) {
        this.blockInteractionType = blockInteractionType;
        this.rawBlockInteractionType = blockInteractionType.ordinal();
        this.itemId = itemId;
    }

    public CauldronInteractEventData(int rawBlockInteractionType, int itemId) {
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
        return EventDataType.CAULDRON_INTERACT;
    }
}
