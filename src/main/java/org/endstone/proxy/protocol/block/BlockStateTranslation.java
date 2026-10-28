package org.endstone.proxy.protocol.block;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.data.BlockChangeEntry;
import org.cloudburstmc.protocol.bedrock.data.SubChunkData;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockSyncedPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSubChunkBlocksPacket;

import java.util.function.IntUnaryOperator;

/**
 * Applies a {@link BlockStateUpgrade} to the packets that actually carry block network ids.
 *
 * <p>Everything here is driven by one rule: a block id crossing a version step has to be rewritten
 * everywhere it appears, or the places that were missed disagree with the places that were not. The
 * covered paths are the ones that decide whether a block is drawn and whether interacting with it
 * lands on the right block:
 *
 * <ul>
 *   <li>{@link LevelChunkPacket} and {@link SubChunkPacket} — the world itself, rewritten inside the
 *       opaque payload by {@link SubChunkPaletteRewriter}, and then given the states 1.26.50
 *       added and the backend cannot supply by {@link StairCornerPass} and {@link BlockJoinPass}.
 *   <li>{@link UpdateBlockPacket}, {@link UpdateBlockSyncedPacket}, {@link UpdateSubChunkBlocksPacket}
 *       — every block that changes after the chunk was sent.
 *   <li>{@link InventoryTransactionPacket} and the item-use transaction inside
 *       {@link PlayerAuthInputPacket} — the block the player says they clicked, travelling the other
 *       way, so this one is downgraded rather than upgraded.
 * </ul>
 *
 * <p><b>Known gaps, deliberately.</b> {@code LevelEventPacket} carries a block id in its data field
 * for some events and something else entirely for others, and rewriting the wrong one would corrupt
 * a particle count or a sound id; falling-block entities carry theirs in an entity data field.
 * Neither decides whether the world renders, and both are guessy to identify, so they are left
 * alone: the cost is a block-break particle of the wrong texture, not a missing world.
 */
public final class BlockStateTranslation {

    private final BlockStateUpgrade upgrade;
    private final IntUnaryOperator toNewer;
    private final IntUnaryOperator toOlder;
    private final StairCornerPass stairCorners;
    private final BlockJoinPass blockJoins;

    public BlockStateTranslation(BlockStateUpgrade upgrade, StairIndex stairs, BlockJoinIndex joins,
                                 BlockJoinFaces faces) {
        this.upgrade = upgrade;
        this.toNewer = upgrade::toNewer;
        this.toOlder = upgrade::toOlder;
        this.stairCorners = new StairCornerPass(stairs);
        this.blockJoins = new BlockJoinPass(joins, faces);
    }

    /**
     * A step that only renumbers: every added property takes the table's default, and no pass works
     * out a neighbour-dependent state on the way up. The stair and fence passes above exist because
     * 1.26.50 made those shapes part of the block state; a step that adds nothing of the kind, or
     * whose new states are not worth inventing, needs neither.
     */
    public BlockStateTranslation(BlockStateUpgrade upgrade) {
        this.upgrade = upgrade;
        this.toNewer = upgrade::toNewer;
        this.toOlder = upgrade::toOlder;
        this.stairCorners = null;
        this.blockJoins = null;
    }

    public BlockStateUpgrade upgrade() {
        return upgrade;
    }

    /** The stair corner pass, for the relay to read a finished chunk back with; null on a renumber-only step. */
    public StairCornerPass stairCorners() {
        return stairCorners;
    }

    /** The fence, pane and bar pass, for the relay to read a finished chunk back with; null on a renumber-only step. */
    public BlockJoinPass blockJoins() {
        return blockJoins;
    }

    /**
     * Whether a client on {@code clientProtocol} talking to a backend on {@code backendProtocol} is
     * the pairing this table carries blocks <em>up</em> for, which is the only one whose stair
     * corners have to be invented.
     *
     * <p>"At or above" the table's newer protocol, not equal to it. The client's number is not the
     * table's whenever a release renumbers past it: 2193 shipped where the 1.26.50 table says 2192,
     * and 2225 sits on top of both, and in either case the chain still carries the blocks up through
     * this table, so the shapes it invents still have seams to settle.
     */
    public boolean upgradesFor(int clientProtocol, int backendProtocol) {
        return clientProtocol >= upgrade.toProtocol() && backendProtocol <= upgrade.fromProtocol();
    }

    /** Renumber towards the newer version. */
    public IntUnaryOperator toNewer() {
        return toNewer;
    }

    /** Renumber towards the older version. */
    public IntUnaryOperator toOlder() {
        return toOlder;
    }

    /**
     * A packet on its way to the player, renumbered with {@code map}.
     *
     * <p>The direction is the caller's to choose, because which end is newer depends on the pairing:
     * a 1.26.50 player on a 1.26.45 backend needs {@link #toNewer()} here, and a 1.26.45 player on a
     * 1.26.50 backend needs {@link #toOlder()}. Both happen, and one table serves both.
     *
     * <p>Mutates the packet in place and returns it; the relay owns it by this point, and a chunk
     * payload is swapped for a rewritten buffer with the original released.
     */
    public BedrockPacket rewriteClientbound(BedrockPacket packet, IntUnaryOperator map) {
        if (packet instanceof LevelChunkPacket chunk) {
            rewriteChunk(chunk, map);
        } else if (packet instanceof SubChunkPacket subChunk) {
            rewriteSubChunks(subChunk, map);
        } else if (packet instanceof UpdateBlockPacket update) {
            update.setDefinition(map(update.getDefinition(), map));
        } else if (packet instanceof UpdateBlockSyncedPacket update) {
            update.setDefinition(map(update.getDefinition(), map));
        } else if (packet instanceof UpdateSubChunkBlocksPacket update) {
            rewriteSubChunkBlocks(update, map);
        }
        return packet;
    }

    /** A packet on its way from the player to the backend, renumbered the opposite way. */
    public BedrockPacket rewriteServerbound(BedrockPacket packet, IntUnaryOperator map) {
        if (packet instanceof InventoryTransactionPacket transaction) {
            noteClientBlock(transaction.getBlockDefinition());
            transaction.setBlockDefinition(map(transaction.getBlockDefinition(), map));
        } else if (packet instanceof PlayerAuthInputPacket input && input.getItemUseTransaction() != null) {
            var itemUse = input.getItemUseTransaction();
            noteClientBlock(itemUse.getBlockDefinition());
            itemUse.setBlockDefinition(map(itemUse.getBlockDefinition(), map));
        }
        return packet;
    }

    /**
     * Records the block the player says they clicked, before it is renumbered back down.
     *
     * <p>This is the only place the proxy ever learns what the <em>client</em> holds rather than what
     * was sent to it, and for the 1.26.50 connection states that is the whole question: a click on a
     * fence that comes back as an armed id means the client took the arms the chunk pass gave it, and
     * one that comes back armless means they never landed. It is counted rather than logged per
     * click, and reported once with the rest of the census.
     */
    private void noteClientBlock(BlockDefinition definition) {
        if (definition != null && blockJoins != null) {
            blockJoins.describeFromClient(definition.getRuntimeId());
        }
    }

    private void rewriteChunk(LevelChunkPacket chunk, IntUnaryOperator map) {
        if (chunk.isCachingEnabled()) {
            // The payload is a list of blob hashes, not block data. Cross-protocol pairings turn the
            // blob cache off for exactly this kind of reason, so this is a guard, not a path.
            return;
        }
        setData(chunk, SubChunkPaletteRewriter.rewrite(chunk.getData(), chunk.getSubChunksLength(), map));
        if (map == toNewer && stairCorners != null) {
            // Only on the way up. Going down, the corner and the arms are being dropped rather than
            // invented, and the older client works both out for itself as it always did.
            setData(chunk, stairCorners.apply(chunk.getData(), chunk.getSubChunksLength()));
            setData(chunk, blockJoins.apply(chunk.getData(), chunk.getSubChunksLength()));
        }
    }

    private static void setData(LevelChunkPacket chunk, ByteBuf replacement) {
        if (replacement == null) {
            return;
        }
        ByteBuf previous = chunk.getData();
        chunk.setData(replacement);
        if (previous != null) {
            previous.release();
        }
    }

    private void rewriteSubChunks(SubChunkPacket packet, IntUnaryOperator map) {
        if (packet.isCacheEnabled()) {
            return;
        }
        for (SubChunkData subChunk : packet.getSubChunks()) {
            ByteBuf data = subChunk.getData();
            if (data == null) {
                continue;
            }
            ByteBuf rewritten = SubChunkPaletteRewriter.rewrite(data, 1, map);
            if (rewritten != null) {
                subChunk.setData(rewritten);
                data.release();
                data = rewritten;
            }
            if (map == toNewer && stairCorners != null) {
                ByteBuf cornered = stairCorners.apply(data, 1);
                if (cornered != null) {
                    subChunk.setData(cornered);
                    data.release();
                    data = cornered;
                }
                ByteBuf joined = blockJoins.apply(data, 1);
                if (joined != null) {
                    subChunk.setData(joined);
                    data.release();
                }
            }
        }
    }

    private void rewriteSubChunkBlocks(UpdateSubChunkBlocksPacket packet, IntUnaryOperator map) {
        // BlockChangeEntry is immutable, so a changed id means a replacement entry rather than a set.
        packet.getStandardBlocks().replaceAll(entry -> remap(entry, map));
        packet.getExtraBlocks().replaceAll(entry -> remap(entry, map));
    }

    private static BlockChangeEntry remap(BlockChangeEntry entry, IntUnaryOperator map) {
        BlockDefinition mapped = map(entry.getDefinition(), map);
        if (mapped == entry.getDefinition()) {
            return entry;
        }
        return new BlockChangeEntry(entry.getPosition(), mapped, entry.getUpdateFlags(),
                entry.getMessageEntityId(), entry.getMessageType());
    }

    private static BlockDefinition map(BlockDefinition definition, IntUnaryOperator map) {
        if (definition == null) {
            return null;
        }
        int mapped = map.applyAsInt(definition.getRuntimeId());
        return mapped == definition.getRuntimeId() ? definition : new TranslatedBlockDefinition(mapped);
    }

    /**
     * A bare id, which is all a relay ever needs. The codecs write {@code getRuntimeId()} and nothing
     * else, and the proxy has no block identifiers or state NBT to put in a
     * {@code SimpleBlockDefinition} — it never sees a palette, only hashes.
     */
    private record TranslatedBlockDefinition(int runtimeId) implements BlockDefinition {
        @Override
        public int getRuntimeId() {
            return runtimeId;
        }
    }
}
