package org.endstone.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.HeightMapDataType;
import org.cloudburstmc.protocol.bedrock.data.SubChunkData;
import org.cloudburstmc.protocol.bedrock.data.SubChunkRequestResult;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Carries the two terrain-streaming packets across the 1.26.30 &rarr; 1.26.40 hop in the exact
 * shapes a live BDS backend sends them, and checks a 1.26.40 reader can consume the result.
 *
 * <p>The generic sweeps cannot cover this and it is worth being explicit about why, because the
 * gap looks like coverage. {@link PacketPopulator} skips {@code final} fields and any field that is
 * already non-null, so {@code SubChunkPacket.subChunks} and {@code LevelChunkPacket.blobIds} stay
 * empty and every boolean stays false. {@code SubChunkPacket}'s entire wire shape lives inside its
 * entry list, and {@code LevelChunkPacket}'s interesting branch is {@code requestSubChunks == true}
 * — so a sweep of populated instances exercises neither. It passes without touching the code that
 * runs 240 times a session.
 *
 * <p>The shapes below are taken from a real 1.26.40-client-on-1.26.30-backend capture rather than
 * invented: every {@code LevelChunkPacket} in it arrives in sub-chunk request mode with a limit
 * between 8 and 11 and caching off, and {@code SubChunkPacket} entries arrive as a mix of
 * {@code HAS_DATA} and {@code TOO_HIGH} heightmaps. Those are the branches that matter.
 *
 * <p>1.26.40 rewrote both envelopes — the count/limit union became a count plus an optional, the
 * blob list became unconditional, and the sub-chunk entry's presence rules moved from being implied
 * by {@code result} and {@code heightMapType} onto explicit booleans. None of that is caught by
 * "it encoded without throwing", which is all the sweeps assert.
 */
class CrossProtocolChunkStreamTest {

    private static final BedrockCodec BACKEND = Bedrock_v1001.CODEC;
    private static final BedrockCodec CLIENT = Bedrock_v2168.CODEC;

    /**
     * The shape every single chunk in the capture had: the backend streams terrain by telling the
     * client to ask for sub-chunks, and the payload carries only biomes and border blocks.
     */
    @Test
    void levelChunkInSubChunkRequestModeReachesA1_26_40Client() {
        LevelChunkPacket sent = new LevelChunkPacket();
        sent.setChunkX(-7);
        sent.setChunkZ(61);
        sent.setDimension(0);
        sent.setRequestSubChunks(true);
        sent.setSubChunkLimit(10);
        sent.setCachingEnabled(false);
        sent.setData(biomePayload());

        LevelChunkPacket received = hop(sent, LevelChunkPacket.class);
        assertEquals(-7, received.getChunkX(), "chunkX");
        assertEquals(61, received.getChunkZ(), "chunkZ");
        assertEquals(0, received.getDimension(), "dimension");
        assertEquals(true, received.isRequestSubChunks(), "requestSubChunks");
        assertEquals(10, received.getSubChunkLimit(), "subChunkLimit");
        assertEquals(biomePayload().readableBytes(), received.getData().readableBytes(), "payload bytes");
    }

    /**
     * The other request-mode encoding. On 1.26.30 this is the {@code -1} count with no trailing
     * short; on 1.26.40 it is an optional carrying -1. The two are easy to conflate with "absent".
     */
    @Test
    void levelChunkInLimitlessRequestModeReachesA1_26_40Client() {
        LevelChunkPacket sent = new LevelChunkPacket();
        sent.setRequestSubChunks(true);
        sent.setSubChunkLimit(-1);
        sent.setData(biomePayload());

        LevelChunkPacket received = hop(sent, LevelChunkPacket.class);
        assertEquals(true, received.isRequestSubChunks(), "requestSubChunks");
        assertEquals(-1, received.getSubChunkLimit(), "subChunkLimit");
    }

    /**
     * A chunk sent inline rather than on request. Here the limit must be <em>absent</em> on the
     * 1.26.40 side, not present-and-zero: a client told to request sub-chunks for a chunk it has
     * already been given will ask for terrain the backend considers delivered.
     */
    @Test
    void levelChunkWithInlineSubChunksReachesA1_26_40Client() {
        LevelChunkPacket sent = new LevelChunkPacket();
        sent.setRequestSubChunks(false);
        sent.setSubChunksLength(5);
        sent.setData(biomePayload());

        LevelChunkPacket received = hop(sent, LevelChunkPacket.class);
        assertEquals(false, received.isRequestSubChunks(), "requestSubChunks");
        assertEquals(5, received.getSubChunksLength(), "subChunksLength");
    }

    /**
     * Mixed heightmap kinds in one packet, as the capture shows: 1.26.30 implies the 256-byte
     * heightmap from {@code heightMapType == HAS_DATA}, while 1.26.40 writes an explicit presence
     * bool. An entry whose type says one thing and whose bool says another leaves the client's
     * reader at the wrong offset for every entry after it.
     */
    @Test
    void subChunkWithMixedHeightmapsReachesA1_26_40Client() {
        SubChunkPacket sent = new SubChunkPacket();
        sent.setDimension(0);
        sent.setCacheEnabled(false);
        sent.setCenterPosition(Vector3i.from(-14, 0, 58));
        sent.getSubChunks().add(entry(Vector3i.from(5, 4, -6), HeightMapDataType.HAS_DATA));
        sent.getSubChunks().add(entry(Vector3i.from(5, 3, -5), HeightMapDataType.TOO_HIGH));
        sent.getSubChunks().add(entry(Vector3i.from(6, 3, -4), HeightMapDataType.HAS_DATA));

        SubChunkPacket received = hop(sent, SubChunkPacket.class);
        assertEquals(Vector3i.from(-14, 0, 58), received.getCenterPosition(), "centerPosition");
        assertEquals(3, received.getSubChunks().size(), "entry count");

        assertEquals(Vector3i.from(5, 4, -6), received.getSubChunks().get(0).getPosition(), "entry 0 position");
        assertEquals(SubChunkRequestResult.SUCCESS, received.getSubChunks().get(0).getResult(), "entry 0 result");
        assertEquals(HeightMapDataType.HAS_DATA, received.getSubChunks().get(0).getHeightMapType(), "entry 0 heightMapType");
        assertNotNull(received.getSubChunks().get(0).getHeightMapData(), "entry 0 heightMapData");
        assertEquals(256, received.getSubChunks().get(0).getHeightMapData().readableBytes(), "entry 0 heightMapData bytes");

        assertEquals(HeightMapDataType.TOO_HIGH, received.getSubChunks().get(1).getHeightMapType(), "entry 1 heightMapType");
        assertNull(received.getSubChunks().get(1).getHeightMapData(), "entry 1 carries no heightmap");

        assertEquals(Vector3i.from(6, 3, -4), received.getSubChunks().get(2).getPosition(), "entry 2 position");
        assertEquals(256, received.getSubChunks().get(2).getHeightMapData().readableBytes(), "entry 2 heightMapData bytes");
    }

    /**
     * Caching is disabled for this pairing today ("Disabling backend blob cache for cross-protocol
     * join"), but the blob id moved from being implied by the packet-level cache flag to being its
     * own optional, so the two encodings disagree about a field that is present in one and absent in
     * the other. Pinned so re-enabling the cache fails here rather than in front of a player.
     */
    @Test
    void subChunkWithCachingEnabledReachesA1_26_40Client() {
        SubChunkPacket sent = new SubChunkPacket();
        sent.setDimension(0);
        sent.setCacheEnabled(true);
        sent.setCenterPosition(Vector3i.from(-14, 0, 58));
        SubChunkData cached = entry(Vector3i.from(2, 5, -8), HeightMapDataType.HAS_DATA);
        cached.setBlobId(0x0123456789ABCDEFL);
        sent.getSubChunks().add(cached);

        SubChunkPacket received = hop(sent, SubChunkPacket.class);
        assertEquals(1, received.getSubChunks().size(), "entry count");
        assertEquals(0x0123456789ABCDEFL, received.getSubChunks().get(0).getBlobId(), "blobId");
    }

    /**
     * The branch the audit list put first, and the one branch of {@code SubChunkSerializer_v818} whose
     * two versions genuinely disagree about whether a payload exists: 1.26.30 implies the payload from
     * {@code result != SUCCESS_ALL_AIR || !cacheEnabled}, while 1.26.40 writes an explicit presence
     * bool for it.
     *
     * <p>With caching off — which is the only configuration this pairing runs in, because the proxy
     * disables the backend blob cache for a cross-protocol join — the 1.26.30 reader takes the
     * {@code !cacheEnabled} arm and reads a payload even for an all-air sub-chunk, so the two
     * encodings agree and the hop is lossless. Pinned because "they agree here" is a claim about a
     * branch, not about the code, and re-enabling the cache changes which arm runs.
     *
     * <p>Worth recording alongside: no capture of this pairing contains a single
     * {@code SUCCESS_ALL_AIR} entry. The client only requests sub-chunks up to the
     * {@code subChunkLimit} the {@code LevelChunk} advertises, which is the highest non-air sub-chunk,
     * so it never asks for the all-air column above the terrain. The branch is unreachable in
     * practice as well as correct.
     */
    @Test
    void allAirSubChunksSurviveTheHopWithCachingOff() {
        SubChunkPacket sent = new SubChunkPacket();
        sent.setCacheEnabled(false);
        sent.setCenterPosition(Vector3i.from(31, 0, 9));
        SubChunkData allAir = entry(Vector3i.from(0, 6, 4), HeightMapDataType.TOO_HIGH);
        allAir.setResult(SubChunkRequestResult.SUCCESS_ALL_AIR);
        sent.getSubChunks().add(allAir);

        SubChunkPacket received = hop(sent, SubChunkPacket.class);
        assertEquals(1, received.getSubChunks().size(), "entry count");
        assertEquals(SubChunkRequestResult.SUCCESS_ALL_AIR, received.getSubChunks().get(0).getResult());
        assertNotNull(received.getSubChunks().get(0).getData(),
                "with caching off 1.26.30 writes a payload even for all-air, so 1.26.40 must be told "
                        + "one is present or its reader lands mid-stream on the next entry");
    }

    /**
     * The render heightmap, which is the only field of this packet no capture has ever printed and
     * therefore the only value on it never checked against what 1.26.40 accepts. The two slots do not
     * have the same accepted range in the {@code r26_u4} dump — the terrain heightmap allows 0-3 and
     * the render heightmap allows 0-4 — so they cannot be assumed interchangeable.
     */
    @Test
    void renderHeightmapsCarryAcrossTheHopIndependentlyOfTheTerrainHeightmap() {
        SubChunkPacket sent = new SubChunkPacket();
        sent.setCacheEnabled(false);
        sent.setCenterPosition(Vector3i.from(31, 0, 9));
        SubChunkData both = entry(Vector3i.from(0, -4, 4), HeightMapDataType.HAS_DATA);
        both.setRenderHeightMapType(HeightMapDataType.HAS_DATA);
        both.setRenderHeightMapData(Unpooled.wrappedBuffer(new byte[256]));
        sent.getSubChunks().add(both);
        // COPIED is legal in the render slot and illegal in the terrain slot; a shared reader that
        // conflated the two would surface here.
        SubChunkData copied = entry(Vector3i.from(0, -3, 4), HeightMapDataType.TOO_LOW);
        copied.setRenderHeightMapType(HeightMapDataType.COPIED);
        sent.getSubChunks().add(copied);

        SubChunkPacket received = hop(sent, SubChunkPacket.class);
        assertEquals(2, received.getSubChunks().size(), "entry count");

        SubChunkData first = received.getSubChunks().get(0);
        assertEquals(HeightMapDataType.HAS_DATA, first.getHeightMapType(), "heightMapType");
        assertEquals(256, first.getHeightMapData().readableBytes(), "heightMapData bytes");
        assertEquals(HeightMapDataType.HAS_DATA, first.getRenderHeightMapType(), "renderHeightMapType");
        assertNotNull(first.getRenderHeightMapData(), "renderHeightMapData");
        assertEquals(256, first.getRenderHeightMapData().readableBytes(), "renderHeightMapData bytes");

        SubChunkData second = received.getSubChunks().get(1);
        assertEquals(HeightMapDataType.TOO_LOW, second.getHeightMapType(), "heightMapType");
        assertNull(second.getHeightMapData(), "TOO_LOW carries no heightmap");
        assertEquals(HeightMapDataType.COPIED, second.getRenderHeightMapType(), "renderHeightMapType");
        assertNull(second.getRenderHeightMapData(), "COPIED carries no render heightmap");
    }

    /**
     * Both enums cross the hop by {@code ordinal()} and come back by {@code values()[byte]}, which is
     * the defect family that has already produced three bugs on this pairing. Checked against the
     * {@code r26_u4} enum dumps: {@code SubChunkRequestResult} is Undefined=0 through SuccessAllAir=6
     * and {@code HeightMapDataType} is NoData=0 through AllCopied=4, both unchanged from 1.26.30.
     * This asserts the tree's enums still agree with those wire values, so a future insertion breaks
     * a test rather than a session.
     */
    @Test
    void theTwoOrdinalMappedEnumsStillMatchThe1_26_40WireValues() {
        assertEquals(0, SubChunkRequestResult.UNDEFINED.ordinal(), "Undefined");
        assertEquals(1, SubChunkRequestResult.SUCCESS.ordinal(), "Success");
        assertEquals(2, SubChunkRequestResult.CHUNK_NOT_FOUND.ordinal(), "LevelChunkDoesntExist");
        assertEquals(3, SubChunkRequestResult.INVALID_DIMENSION.ordinal(), "WrongDimension");
        assertEquals(4, SubChunkRequestResult.PLAYER_NOT_FOUND.ordinal(), "PlayerDoesntExist");
        assertEquals(5, SubChunkRequestResult.INDEX_OUT_OF_BOUNDS.ordinal(), "IndexOutOfBounds");
        assertEquals(6, SubChunkRequestResult.SUCCESS_ALL_AIR.ordinal(), "SuccessAllAir");
        assertEquals(7, SubChunkRequestResult.values().length,
                "1.26.40 accepts 1-6 for this field; a new constant anywhere but the end shifts every "
                        + "value the backend sends");

        assertEquals(0, HeightMapDataType.NO_DATA.ordinal(), "NoData");
        assertEquals(1, HeightMapDataType.HAS_DATA.ordinal(), "HasData");
        assertEquals(2, HeightMapDataType.TOO_HIGH.ordinal(), "AllTooHigh");
        assertEquals(3, HeightMapDataType.TOO_LOW.ordinal(), "AllTooLow");
        assertEquals(4, HeightMapDataType.COPIED.ordinal(), "AllCopied");
        assertEquals(5, HeightMapDataType.values().length, "1.26.40 defines exactly these five");
    }

    private static SubChunkData entry(Vector3i position, HeightMapDataType heightMapType) {
        SubChunkData data = new SubChunkData();
        data.setPosition(position);
        data.setResult(SubChunkRequestResult.SUCCESS);
        data.setData(Unpooled.wrappedBuffer(new byte[527]));
        data.setHeightMapType(heightMapType);
        if (heightMapType == HeightMapDataType.HAS_DATA) {
            data.setHeightMapData(Unpooled.wrappedBuffer(new byte[256]));
        }
        // The capture never carries a render heightmap, and 1.26.30 writes the type unconditionally.
        data.setRenderHeightMapType(HeightMapDataType.NO_DATA);
        return data;
    }

    /**
     * The biome section encoding a request-mode chunk carries: palette-only sections followed by
     * "same as the previous section" markers, then the border-block count. Copied from the capture
     * so the payload is a real one rather than filler — it is passed through opaquely by both
     * versions, and this test would not notice if that ever stopped being true.
     */
    private static ByteBuf biomePayload() {
        byte[] payload = new byte[34];
        int index = 0;
        for (int section = 0; section < 9; section++) {
            payload[index++] = 0x01;
            payload[index++] = 0x08;
        }
        while (index < payload.length - 1) {
            payload[index++] = (byte) 0xFF;
        }
        payload[index] = 0x00;
        return Unpooled.wrappedBuffer(payload);
    }

    /**
     * Does exactly what the relay does — encode as the backend, decode with the backend's codec,
     * re-encode with the client's — and then reads the result back with the client's codec,
     * requiring every byte to be accounted for.
     */
    private static <T extends BedrockPacket> T hop(T sent, Class<T> type) {
        int id = BACKEND.getPacketDefinition(type).getId();
        assertEquals(id, CLIENT.getPacketDefinition(type).getId(), "packet id differs between codecs");

        BedrockPacket decodedFromBackend = transcode(BACKEND, sent, id, buffer ->
                assertEquals(0, buffer.readableBytes(),
                        type.getSimpleName() + " does not survive a round trip through the backend's own codec"));

        BedrockPacket forClient = transcode(CLIENT, decodedFromBackend, id, buffer ->
                assertEquals(0, buffer.readableBytes(),
                        type.getSimpleName() + " was encoded for a 1.26.40 client but its reader left "
                                + buffer.readableBytes() + " bytes unconsumed; the client's parser would be"
                                + " left mid-stream and close the connection"));

        ReferenceCountUtil.release(decodedFromBackend);
        return type.cast(forClient);
    }

    private static BedrockPacket transcode(BedrockCodec codec, BedrockPacket packet, int id, Consumer<ByteBuf> check) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(), buffer, packet);
            BedrockPacket decoded = codec.tryDecode(codec.createHelper(), buffer, id);
            check.accept(buffer);
            return decoded;
        } finally {
            buffer.release();
        }
    }
}
