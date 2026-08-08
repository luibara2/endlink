package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

/**
 * Corrections applied to a backend {@link StartGamePacket} <em>only</em> when the client and backend
 * speak different protocol versions.
 *
 * <p>The sibling of {@link StartGameClientFixups}, and deliberately separate from it: that class
 * holds client-behaviour workarounds that must apply to every pairing, and its javadoc records what
 * happened the last time a correction was wrongly gated on {@code isCrossProtocol()}. These are the
 * opposite case — corrections that are only correct <em>because</em> the versions differ, and that
 * would suppress a genuine consistency check if applied to a matched pair.</p>
 *
 * @param clearedBlockRegistryChecksum the backend's block palette checksum was dropped; see
 *                                     {@link #apply}
 * @param indexBasedBlockIds           the backend sends palette-index block ids rather than hashed
 *                                     ones, which no amount of StartGame fixing can reconcile
 *                                     across versions
 */
record CrossProtocolStartGameFixups(
        long clearedBlockRegistryChecksum,
        boolean indexBasedBlockIds
) {

    static final CrossProtocolStartGameFixups NONE = new CrossProtocolStartGameFixups(0L, false);

    /**
     * Clears the block type registry checksum so the client stops rejecting the world with "blocks
     * between client and server do not match".
     *
     * <p>StartGame carries a checksum over the server's block type registry and the client compares
     * it against a checksum of its own palette. Across versions those palettes belong to different
     * Minecraft releases, so they can never agree, and the client disconnects before rendering a
     * single chunk.</p>
     *
     * <p>Zero is the documented opt-out rather than a trick — gophertunnel's field comment for
     * {@code ServerBlockStateChecksum} reads "This can simply be left empty, and the client will
     * avoid trying to verify it." The check exists to catch a server whose palette has drifted from
     * the client's; for a proxy bridging two versions that drift is the entire premise, so verifying
     * it means nothing.</p>
     *
     * <p>Left alone at matching versions, where the checksum is a real check that would catch a
     * genuinely corrupt palette — zeroing it everywhere would hide that.</p>
     */
    static CrossProtocolStartGameFixups apply(StartGamePacket startGame, boolean crossProtocol) {
        if (!crossProtocol) {
            return NONE;
        }

        long cleared = startGame.getBlockRegistryChecksum();
        if (cleared != 0L) {
            startGame.setBlockRegistryChecksum(0L);
        }

        // Hashed ids are what makes the rest of the hop work at all. With them on, a block's network
        // id is a hash of its state NBT, so every block whose definition did not change between the
        // two versions keeps the same id and chunks decode correctly. With them off, ids are indices
        // into each version's own palette, nothing lines up, and chunks would be wrong even with the
        // checksum silenced — that needs a real palette remap, which this proxy does not do.
        return new CrossProtocolStartGameFixups(cleared, !startGame.isBlockNetworkIdsHashed());
    }
}
