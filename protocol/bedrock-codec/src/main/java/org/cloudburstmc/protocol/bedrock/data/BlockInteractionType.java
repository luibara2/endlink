package org.cloudburstmc.protocol.bedrock.data;

public enum BlockInteractionType {
    NONE,
    EXTEND,
    CLONE,
    LOCK,
    CREATE,
    CREATE_LOCATOR,
    RENAME,
    ITEM_PLACED,
    ITEM_REMOVED,
    COOKING,
    DOUSING,
    LIGHTING,
    HAYSTACK,
    FILLED,
    EMPTIED,
    ADD_DYE,
    DYE_ITEM,
    CLEAR_ITEM,
    ENCHANT_ARROW,
    COMPOST_ITEM_PLACE,
    RECOVERED_BONEMEAL,
    BOOK_PLACED,
    BOOK_OPEN,
    DISENCHANT,
    REPAIR,
    DISENCHANT_AND_REPAIR;

    private static final BlockInteractionType[] VALUES = values();

    /**
     * Resolves a wire ordinal, falling back to {@link #NONE} for values this build does not know.
     *
     * <p>The wire carries this as an unsigned varint index into a list the game extends between
     * versions, so indexing {@code values()} directly throws on anything newer. Callers that have to
     * put the value back on the wire must relay the raw ordinal, not this enum.
     */
    public static BlockInteractionType byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : NONE;
    }
}
