package org.endstone.proxy.backend;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.HeightMapDataType;
import org.cloudburstmc.protocol.bedrock.data.SubChunkData;
import org.cloudburstmc.protocol.bedrock.data.SubChunkRequestResult;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkRequestPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.endstone.proxy.protocol.CanonicalProtocol;
import org.endstone.proxy.protocol.block.BedrockBlockStateHash;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proxy answering a request-mode client's sub-chunk requests from a backend's whole chunks.
 *
 * <p>The payloads here are built the way PowerNukkitX writes them: version 9 sub-chunks with two
 * storages, one biome palette per sub-chunk sent rather than per section, the border-block list,
 * then block entities (or a lone end tag when there are none).
 */
final class SubChunkBridgeTest {

    private static final int STONE = 0x1234567;
    private static final int GLASS = 0x7654321;

    @Test
    void aWholeChunkBecomesTheShellARequestModeServerSends() {
        SubChunkBridge bridge = new SubChunkBridge();
        LevelChunkPacket chunk = pnxChunk(3, -2, 6, null);

        SubChunkBridge.Bridged bridged = bridge.bridge(chunk);

        assertNotNull(bridged);
        LevelChunkPacket shell = bridged.shell();
        assertTrue(shell.isRequestSubChunks());
        assertEquals(0, shell.getSubChunksLength());
        assertEquals(6, shell.getSubChunkLimit());
        assertEquals(3, shell.getChunkX());
        assertEquals(-2, shell.getChunkZ());
        // 6 real biome sections, 18 copy-last pads to fill the overworld's 24, then the border list.
        ByteBuf expected = Unpooled.buffer();
        for (int i = 0; i < 6; i++) {
            writeUniformBiome(expected, 1);
        }
        for (int i = 6; i < 24; i++) {
            expected.writeByte((127 << 1) | 1);
        }
        expected.writeByte(0);
        assertEquals(expected, shell.getData());
        assertTrue(bridged.answers().isEmpty());
        assertTrue(bridge.isActive());
    }

    @Test
    void requestsAreAnsweredFromTheKeptSubChunks() {
        SubChunkBridge bridge = new SubChunkBridge();
        NbtMap sign = NbtMap.builder().putString("id", "Sign").putInt("x", 50).putInt("y", -60).putInt("z", -20).build();
        bridge.bridge(pnxChunk(3, -2, 6, sign));

        // BDS clients send absolute positions against a zero centre.
        SubChunkPacket answer = bridge.answer(request(Vector3i.ZERO,
                Vector3i.from(3, -4, -2), Vector3i.from(3, -3, -2), Vector3i.from(3, 10, -2), Vector3i.from(3, 20, -2)));

        assertNotNull(answer);
        assertEquals(4, answer.getSubChunks().size());

        SubChunkData stone = answer.getSubChunks().get(0);
        assertEquals(SubChunkRequestResult.SUCCESS, stone.getResult());
        assertEquals(Vector3i.from(3, -4, -2), stone.getPosition());
        // The sub-chunk exactly as sent, followed by the block entity that sits in it.
        ByteBuf expected = Unpooled.buffer();
        writeSubChunk(expected, -4, STONE);
        writeNbt(expected, sign);
        assertEquals(expected, stone.getData());
        // Stone fills the bottom sub-chunk, so open sky starts above it in every column.
        assertEquals(HeightMapDataType.TOO_HIGH, stone.getHeightMapType());

        SubChunkData air = answer.getSubChunks().get(1);
        assertEquals(SubChunkRequestResult.SUCCESS, air.getResult());
        assertEquals(HeightMapDataType.HAS_DATA, air.getHeightMapType());
        assertEquals(HeightMapDataType.COPIED, air.getRenderHeightMapType());
        // Sky starts one above the glass at the bottom of y=-3 in column x=0, z=0, and right at
        // the floor of y=-3 everywhere else. The map is z-major, as BDS writes it.
        assertEquals(1, air.getHeightMapData().getByte(SubChunkBridge.heightIndex(0, 0)));
        assertEquals(0, air.getHeightMapData().getByte(SubChunkBridge.heightIndex(1, 0)));
        assertEquals(16, SubChunkBridge.heightIndex(0, 1));

        SubChunkData aboveEverything = answer.getSubChunks().get(2);
        assertEquals(SubChunkRequestResult.SUCCESS_ALL_AIR, aboveEverything.getResult());
        assertNull(aboveEverything.getData());
        assertEquals(HeightMapDataType.TOO_LOW, aboveEverything.getHeightMapType());

        assertEquals(SubChunkRequestResult.INDEX_OUT_OF_BOUNDS, answer.getSubChunks().get(3).getResult());
        assertEncodes(answer);
        answer.release();
    }

    @Test
    void requestsForAColumnNotYetSentWaitForIt() {
        SubChunkBridge bridge = new SubChunkBridge();

        assertNull(bridge.answer(request(Vector3i.from(1, 0, 1), Vector3i.from(2, -4, -3))));

        SubChunkBridge.Bridged bridged = bridge.bridge(pnxChunk(3, -2, 6, null));
        assertEquals(1, bridged.answers().size());
        SubChunkPacket answer = bridged.answers().get(0);
        assertEquals(Vector3i.from(1, 0, 1), answer.getCenterPosition());
        assertEquals(Vector3i.from(2, -4, -3), answer.getSubChunks().get(0).getPosition());
        assertEquals(SubChunkRequestResult.SUCCESS, answer.getSubChunks().get(0).getResult());
        assertEncodes(answer);
        answer.release();
    }

    @Test
    void blockChangesReachTheKeptSubChunks() {
        SubChunkBridge bridge = new SubChunkBridge();
        bridge.bridge(pnxChunk(0, 0, 6, null));

        bridge.updateBlock(Vector3i.from(5, 100, 7), 0, STONE);

        SubChunkPacket answer = bridge.answer(request(Vector3i.ZERO, Vector3i.from(0, 6, 0)));
        SubChunkData changed = answer.getSubChunks().get(0);
        assertEquals(SubChunkRequestResult.SUCCESS, changed.getResult());
        assertEquals(HeightMapDataType.HAS_DATA, changed.getHeightMapType());
        assertEquals(100 + 1 - 96, changed.getHeightMapData().getByte(SubChunkBridge.heightIndex(5, 7)));
        answer.release();

        bridge.updateBlock(Vector3i.from(5, 100, 7), 0, BedrockBlockStateHash.AIR);
        answer = bridge.answer(request(Vector3i.ZERO, Vector3i.from(0, 6, 0)));
        assertEquals(SubChunkRequestResult.SUCCESS_ALL_AIR, answer.getSubChunks().get(0).getResult());
        answer.release();
    }

    @Test
    void manyChangesToOneSubChunkAllReachTheAnswer() {
        SubChunkBridge bridge = new SubChunkBridge();
        bridge.bridge(pnxChunk(0, 0, 6, null));

        bridge.updateBlock(Vector3i.from(1, 96, 1), 0, STONE);
        bridge.updateBlock(Vector3i.from(2, 97, 3), 0, GLASS);
        bridge.updateBlock(Vector3i.from(1, 96, 1), 1, 0x2222222);
        bridge.updateBlock(Vector3i.from(2, 97, 3), 0, BedrockBlockStateHash.AIR);

        SubChunkPacket answer = bridge.answer(request(Vector3i.ZERO, Vector3i.from(0, 6, 0)));
        ByteBuf data = answer.getSubChunks().get(0).getData();
        SubChunkBridge.SubChunkLayers layers = SubChunkBridge.SubChunkLayers.read(data.slice(), 6);
        int[][] blocks = layers.blocks();
        assertEquals(2, blocks.length);
        assertEquals(STONE, blocks[0][org.endstone.proxy.protocol.block.SubChunkStorage.index(1, 0, 1)]);
        assertEquals(0x2222222, blocks[1][org.endstone.proxy.protocol.block.SubChunkStorage.index(1, 0, 1)]);
        assertEquals(BedrockBlockStateHash.AIR, blocks[0][org.endstone.proxy.protocol.block.SubChunkStorage.index(2, 1, 3)]);
        // Sky starts above the stone once the glass over its neighbour is gone again.
        assertEquals(1, answer.getSubChunks().get(0).getHeightMapData().getByte(SubChunkBridge.heightIndex(1, 1)));
        answer.release();
    }

    /**
     * A generator island sends well over a hundred block changes a second. Each one used to decode
     * and re-encode its whole sub-chunk on the backend's event loop, which starved it: acks went
     * out late, the backend throttled, and a clock on the scoreboard ran at a third of real time.
     */
    @Test
    void blockChangesCostNextToNothing() {
        SubChunkBridge bridge = new SubChunkBridge();
        bridge.bridge(pnxChunk(0, 0, 6, null));

        long start = System.nanoTime();
        for (int i = 0; i < 200_000; i++) {
            bridge.updateBlock(Vector3i.from(i & 15, 64 + ((i >> 4) & 15), (i >> 8) & 15), 0, (i & 1) == 0 ? STONE : GLASS);
        }
        long millis = (System.nanoTime() - start) / 1_000_000;

        // Re-encoding per change took several seconds for this; in place it is milliseconds.
        assertTrue(millis < 1500, "200k block changes took " + millis + " ms");
    }

    @Test
    void overBudgetAColumnAlreadyDeliveredGoesBeforeOneStillWaitedOn() {
        SubChunkBridge bridge = new SubChunkBridge();
        bridge.bridge(pnxChunk(100, 100, 6, null));
        bridge.bridge(pnxChunk(200, 200, 6, null));
        Vector3i[] all = new Vector3i[6];
        for (int y = 0; y < 6; y++) {
            all[y] = Vector3i.from(200, -4 + y, 200);
        }
        bridge.answer(request(Vector3i.ZERO, all)).release();

        for (int i = 0; i < SubChunkBridge.MAX_COLUMNS - 1; i++) {
            bridge.bridge(pnxChunk(i, 0, 1, null));
        }

        assertEquals(SubChunkBridge.MAX_COLUMNS, bridge.columnCount());
        // The older column survives because the client has not had it yet; the delivered one went.
        SubChunkPacket waitedOn = bridge.answer(request(Vector3i.ZERO, Vector3i.from(100, -4, 100)));
        assertEquals(SubChunkRequestResult.SUCCESS, waitedOn.getSubChunks().get(0).getResult());
        waitedOn.release();
        assertNull(bridge.answer(request(Vector3i.ZERO, Vector3i.from(200, -4, 200))));
    }

    @Test
    void aNewWorldForgetsEverything() {
        SubChunkBridge bridge = new SubChunkBridge();
        bridge.bridge(pnxChunk(0, 0, 6, null));
        bridge.clear();

        assertFalse(bridge.isActive());
        assertNull(bridge.answer(request(Vector3i.ZERO, Vector3i.from(0, 0, 0))));
    }

    @Test
    void anUnparseableChunkIsLeftAlone() {
        LevelChunkPacket chunk = new LevelChunkPacket();
        chunk.setSubChunksLength(2);
        chunk.setData(Unpooled.wrappedBuffer(new byte[]{42, 1, 2}));

        assertNull(new SubChunkBridge().bridge(chunk));
    }

    @Test
    void aRequestModeChunkIsNotBridged() {
        LevelChunkPacket chunk = pnxChunk(0, 0, 6, null);
        chunk.setRequestSubChunks(true);

        assertNull(new SubChunkBridge().bridge(chunk));
    }

    /**
     * Stone in the bottom sub-chunk, one glass block at the bottom of the next, air in the rest.
     */
    private static LevelChunkPacket pnxChunk(int x, int z, int sent, NbtMap blockEntity) {
        ByteBuf data = Unpooled.buffer();
        for (int i = 0; i < sent; i++) {
            int y = -4 + i;
            if (i == 0) {
                writeSubChunk(data, y, STONE);
            } else if (i == 1) {
                data.writeByte(9);
                data.writeByte(2);
                data.writeByte(y);
                // Two entries at 1 bit: position 0 (x=0, z=0, y=0) is glass.
                data.writeByte((1 << 1) | 1);
                data.writeIntLE(1);
                data.writeZero(127 * 4);
                VarInts.writeInt(data, 2);
                VarInts.writeInt(data, BedrockBlockStateHash.AIR);
                VarInts.writeInt(data, GLASS);
                writeUniformStorage(data, BedrockBlockStateHash.AIR);
            } else {
                writeSubChunk(data, y, BedrockBlockStateHash.AIR);
            }
        }
        for (int i = 0; i < sent; i++) {
            writeUniformBiome(data, 1);
        }
        data.writeByte(0);
        if (blockEntity == null) {
            data.writeByte(0);
        } else {
            writeNbt(data, blockEntity);
        }

        LevelChunkPacket chunk = new LevelChunkPacket();
        chunk.setChunkX(x);
        chunk.setChunkZ(z);
        chunk.setDimension(0);
        chunk.setSubChunksLength(sent);
        chunk.setData(data);
        return chunk;
    }

    private static void writeSubChunk(ByteBuf out, int y, int block) {
        out.writeByte(9);
        out.writeByte(2);
        out.writeByte(y);
        writeUniformStorage(out, block);
        writeUniformStorage(out, BedrockBlockStateHash.AIR);
    }

    /** PowerNukkitX never writes width 0; a uniform storage goes out at its smallest real width. */
    private static void writeUniformStorage(ByteBuf out, int block) {
        out.writeByte((1 << 1) | 1);
        out.writeZero(128 * 4);
        VarInts.writeInt(out, 1);
        VarInts.writeInt(out, block);
    }

    private static void writeUniformBiome(ByteBuf out, int biome) {
        out.writeByte((1 << 1) | 1);
        out.writeZero(512);
        VarInts.writeInt(out, 1);
        VarInts.writeInt(out, biome);
    }

    private static void writeNbt(ByteBuf out, NbtMap tag) {
        try (NBTOutputStream writer = NbtUtils.createNetworkWriter(new ByteBufOutputStream(out))) {
            writer.writeTag(tag);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static SubChunkRequestPacket request(Vector3i center, Vector3i... offsets) {
        SubChunkRequestPacket request = new SubChunkRequestPacket();
        request.setDimension(0);
        request.setSubChunkPosition(center);
        request.getPositionOffsets().addAll(List.of(offsets));
        return request;
    }

    /** The answer has to survive the newest codec, whose optional fields would reject a gap. */
    private static void assertEncodes(SubChunkPacket packet) {
        try {
            roundTrip(packet);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void roundTrip(SubChunkPacket packet) throws Exception {
        BedrockCodec codec = CanonicalProtocol.newest().codec();
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(), buffer, packet);
            SubChunkPacket decoded = (SubChunkPacket) codec.tryDecode(codec.createHelper(), buffer,
                    codec.getPacketDefinition(SubChunkPacket.class).getId());
            assertEquals(packet.getSubChunks().size(), decoded.getSubChunks().size());
            for (int i = 0; i < decoded.getSubChunks().size(); i++) {
                SubChunkData sent = packet.getSubChunks().get(i);
                SubChunkData read = decoded.getSubChunks().get(i);
                assertEquals(sent.getResult(), read.getResult());
                assertEquals(sent.getPosition(), read.getPosition());
                assertEquals(sent.getHeightMapType(), read.getHeightMapType());
                if (sent.getData() != null) {
                    byte[] expected = new byte[sent.getData().readableBytes()];
                    sent.getData().getBytes(sent.getData().readerIndex(), expected);
                    byte[] actual = new byte[read.getData().readableBytes()];
                    read.getData().getBytes(read.getData().readerIndex(), actual);
                    assertArrayEquals(expected, actual);
                }
            }
            decoded.release();
        } finally {
            buffer.release();
        }
    }
}
