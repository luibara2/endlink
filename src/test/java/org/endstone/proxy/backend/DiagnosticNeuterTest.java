package org.endstone.proxy.backend;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.HeightMapDataType;
import org.cloudburstmc.protocol.bedrock.data.SubChunkData;
import org.cloudburstmc.protocol.bedrock.data.SubChunkRequestResult;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.endstone.proxy.backend.BackendRelayPacketHandler.NeuterMode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket.Flag.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code -Dproxy.neuterClientbound}, the instrument that separates packet <em>content</em> from packet
 * <em>rate</em> on the 1.26.40&rarr;1.26.30 disconnect.
 *
 * <p>The bisect with {@code -Dproxy.dropClientbound} showed survival rising from ~5s to 17-54s when
 * {@code MoveEntityDelta} and {@code SetEntityMotion} are suppressed — but those two are also ~60% of
 * all clientbound traffic, so dropping them changes the suspect and the volume together and cannot
 * tell the two apart. Neutering holds the count fixed and changes only the content.</p>
 *
 * <p><b>What these tests are for.</b> The conclusion drawn from a neutered run is only as good as the
 * neuter. If {@code SAME_SIZE} silently changed the encoded length it would reintroduce the very
 * volume confound it exists to remove, and the run would still look like a clean answer — the same
 * shape of failure as a diagnostic flag that never took. So the size claim is asserted here against
 * the real v2168 serializer rather than assumed.</p>
 */
class DiagnosticNeuterTest {

    @Test
    void sameSizeNeuteringDoesNotChangeTheEncodedLength() {
        assertEquals(
                encodedSize(realisticMove()),
                encodedSize(neutered(realisticMove(), NeuterMode.SAME_SIZE)),
                "SAME_SIZE exists to change meaning without changing volume; if the byte count moves, "
                        + "a neutered run is confounded exactly the way a dropped run is");
    }

    @Test
    void sameSizeKeepsThePositionAndRotationPayloadIntact() {
        MoveEntityDeltaPacket neutered = neutered(realisticMove(), NeuterMode.SAME_SIZE);

        assertEquals(1234L, neutered.getRuntimeEntityId());
        assertEquals(128.5f, neutered.getX(), 0.001f);
        assertEquals(71.25f, neutered.getY(), 0.001f);
        assertEquals(-64.75f, neutered.getZ(), 0.001f);
        assertTrue(neutered.getFlags().contains(HAS_X));
        assertTrue(neutered.getFlags().contains(HAS_Y));
        assertTrue(neutered.getFlags().contains(HAS_Z));
        assertTrue(neutered.getFlags().contains(HAS_PITCH));
        assertTrue(neutered.getFlags().contains(HAS_YAW));
        assertTrue(neutered.getFlags().contains(HAS_HEAD_YAW));
    }

    @Test
    void bothModesClearEveryForceFlagTheClientCouldActOn() {
        for (NeuterMode mode : NeuterMode.values()) {
            MoveEntityDeltaPacket neutered = neutered(realisticMove(), mode);

            assertFalse(neutered.isForceMove(), mode + " must not force a move");
            assertFalse(neutered.isForceMoveLocalEntity(),
                    mode + " must not move the player's own entity");
            assertFalse(neutered.isForceCompletion(), mode + " must not force completion");
            assertFalse(neutered.getFlags().contains(TELEPORTING), mode + " left TELEPORTING set");
            assertFalse(neutered.getFlags().contains(FORCE_MOVE_LOCAL_ENTITY),
                    mode + " left FORCE_MOVE_LOCAL_ENTITY set");
            assertFalse(neutered.getFlags().contains(FORCE_COMPLETION),
                    mode + " left FORCE_COMPLETION set");
        }
    }

    /**
     * On-ground true, not false, and it survives the round trip.
     *
     * <p>The sketch for this experiment said "all four booleans false". That would be wrong: a client
     * told an entity is unsupported runs its own physics for it, so a world of airborne entities is
     * itself a known-damaging content — the exact thing the {@code ON_GROUND} fix was written to stop.
     * Neutering must remove suspect content, not substitute different suspect content.
     *
     * <p>{@code MoveEntityDeltaSerializer_v2168} ORs the boolean with the flag-set membership, so this
     * also pins that the flag was cleared rather than left to decide the outcome.
     */
    @Test
    void bothModesReportEntitiesAsGroundedRatherThanAirborne() {
        for (NeuterMode mode : NeuterMode.values()) {
            MoveEntityDeltaPacket neutered = neutered(realisticMove(), mode);

            assertTrue(neutered.isOnGround(), mode + " must not tell the client the entity is falling");
            assertFalse(neutered.getFlags().contains(ON_GROUND),
                    mode + " must decide on-ground from the boolean alone, since v2168 ORs the two");

            MoveEntityDeltaPacket relayed = roundTrip(Bedrock_v2168.CODEC, neutered);
            assertTrue(relayed.isOnGround(), mode + " must still be grounded on the wire");
            assertFalse(relayed.isForceCompletion(), mode + " must still be force-completion free");
        }
    }

    @Test
    void minimalModeDropsEveryOptionalAndIsSmallerOnTheWire() {
        MoveEntityDeltaPacket neutered = neutered(realisticMove(), NeuterMode.MINIMAL);

        assertTrue(neutered.getFlags().isEmpty(), "MINIMAL must leave no HAS_* flag set");
        assertEquals(1234L, neutered.getRuntimeEntityId(), "identity is the one thing MINIMAL keeps");
        assertTrue(encodedSize(neutered) < encodedSize(realisticMove()),
                "MINIMAL cuts content and bytes together — that is the difference from SAME_SIZE, and "
                        + "the reason a MINIMAL-only survival cannot separate content from volume");
    }

    @Test
    void motionIsZeroedWithoutChangingTheEncodedLength() {
        SetEntityMotionPacket real = realisticMotion();
        SetEntityMotionPacket neutered = realisticMotion();
        BackendRelayPacketHandler.neuter(neutered, NeuterMode.SAME_SIZE);

        assertEquals(Vector3f.ZERO, neutered.getMotion());
        assertEquals(9001L, neutered.getRuntimeEntityId());
        assertEquals(4242L, neutered.getTick(), "the tick is identity, not content");
        assertEquals(encodedSize(real), encodedSize(neutered),
                "SetEntityMotion has a fixed shape, so zeroing it must not move a single byte");
    }

    @Test
    void theFlagAcceptsBareNamesThePacketSuffixAndAnExplicitMode() {
        assertEquals(
                Map.of("moveentitydeltapacket", NeuterMode.MINIMAL),
                BackendRelayPacketHandler.parseNeuterSpec("MoveEntityDelta"),
                "a bare name defaults to minimal, matching -Dproxy.dropClientbound's spelling rules");
        assertEquals(
                Map.of("moveentitydeltapacket", NeuterMode.SAME_SIZE,
                        "setentitymotionpacket", NeuterMode.MINIMAL),
                BackendRelayPacketHandler.parseNeuterSpec(" MoveEntityDeltaPacket:samesize , setentitymotion "),
                "the suffix, case and surrounding space must all be tolerated");
        assertEquals(Map.of(), BackendRelayPacketHandler.parseNeuterSpec(""));
        assertEquals(
                Map.of("subchunkpacket", NeuterMode.SAME_SIZE),
                BackendRelayPacketHandler.parseNeuterSpec("SubChunk:samesize"),
                "the neuter is implemented but is reachable only if the name is also in the supported "
                        + "set; omitting it there would reject the flag at startup");
    }

    /**
     * The failure mode this codebase keeps repeating: a diagnostic that did not take, in a run whose
     * result is then read as an answer. A capture was once repeated because "no output" and "the flag
     * was never passed" were indistinguishable. A typo here must stop the proxy, not the experiment.
     */
    @Test
    void anUnneuterablePacketOrAnUnknownModeIsRejectedRatherThanIgnored() {
        assertThrows(IllegalArgumentException.class,
                () -> BackendRelayPacketHandler.parseNeuterSpec("LevelChunk"),
                "no neuter is implemented for LevelChunk, so accepting it would relay it untouched");
        assertThrows(IllegalArgumentException.class,
                () -> BackendRelayPacketHandler.parseNeuterSpec("MoveEntityDelta:sameszie"));
    }

    private static MoveEntityDeltaPacket realisticMove() {
        // The shape a live 1.26.30 backend sends for a mob that is walking: full position, full
        // rotation, on the ground, nothing forced.
        MoveEntityDeltaPacket packet = new MoveEntityDeltaPacket();
        packet.setRuntimeEntityId(1234L);
        packet.setX(128.5f);
        packet.setY(71.25f);
        packet.setZ(-64.75f);
        packet.setPitch(11f);
        packet.setYaw(180f);
        packet.setHeadYaw(178f);
        packet.getFlags().add(HAS_X);
        packet.getFlags().add(HAS_Y);
        packet.getFlags().add(HAS_Z);
        packet.getFlags().add(HAS_PITCH);
        packet.getFlags().add(HAS_YAW);
        packet.getFlags().add(HAS_HEAD_YAW);
        packet.getFlags().add(ON_GROUND);
        packet.getFlags().add(TELEPORTING);
        packet.getFlags().add(FORCE_COMPLETION);
        packet.setOnGround(true);
        packet.setForceMove(true);
        packet.setForceCompletion(true);
        return packet;
    }

    private static SetEntityMotionPacket realisticMotion() {
        SetEntityMotionPacket packet = new SetEntityMotionPacket();
        packet.setRuntimeEntityId(9001L);
        packet.setMotion(Vector3f.from(0.25f, -0.0784f, 0.125f));
        packet.setTick(4242L);
        return packet;
    }

    private static MoveEntityDeltaPacket neutered(MoveEntityDeltaPacket packet, NeuterMode mode) {
        BackendRelayPacketHandler.neuter(packet, mode);
        return packet;
    }

    /**
     * The whole point of neutering {@code SubChunk}: hold the byte count still.
     *
     * <p>{@code -Dproxy.dropClientbound=SubChunk} makes the session immortal, and that reads as the
     * clean isolation this investigation was missing. It is not clean in one respect nobody wrote
     * down: {@code SubChunk} is about 90% of all clientbound <em>bytes</em>, so dropping it is also by
     * far the largest volume cut any experiment here has made — the same confound that wrecked the
     * entity-family bisect, one packet further on. {@code SAME_SIZE} is the run that separates them,
     * and it only means anything if the length really is unchanged.
     */
    @Test
    void sameSizeSubChunkNeuteringDoesNotChangeTheEncodedLength() {
        assertEquals(
                encodedSize(realisticSubChunk()),
                encodedSize(neuteredSubChunk(NeuterMode.SAME_SIZE)),
                "SAME_SIZE must remove the terrain content without removing a single byte, or the run "
                        + "is confounded exactly the way dropClientbound=SubChunk is");
    }

    /**
     * The replacement payload has to be a payload the client will actually read. A neuter that hands
     * the client something unparseable would kill the session by itself and the run would read as
     * "still dies", i.e. as a result.
     */
    @Test
    void theReplacementPayloadIsAValidEmptySubChunkInBothModes() {
        for (NeuterMode mode : NeuterMode.values()) {
            SubChunkPacket neutered = neuteredSubChunk(mode);

            for (SubChunkData entry : neutered.getSubChunks()) {
                ByteBuf data = entry.getData();
                assertTrue(data.readableBytes() >= 2, mode + " left a payload too short to be valid");
                assertEquals(8, data.getByte(data.readerIndex()),
                        mode + " must declare sub-chunk format version 8");
                assertEquals(0, data.getByte(data.readerIndex() + 1),
                        mode + " must declare zero block storages, which is what makes the payload "
                                + "readable without the block registry the proxy does not have");
            }
        }
    }

    /**
     * Everything except the opaque block payload has to survive, because all of it has now been
     * verified correct against gophertunnel PR #481 and the {@code r26_u4} dump. If the neuter also
     * changed a heightmap or a result then a survival would not implicate the payload.
     */
    @Test
    void sameSizeKeepsEveryFieldOfTheEnvelopeThatWasVerifiedCorrect() {
        SubChunkPacket neutered = neuteredSubChunk(NeuterMode.SAME_SIZE);

        assertEquals(Vector3i.from(31, 0, 9), neutered.getCenterPosition(), "centerPosition");
        assertFalse(neutered.isCacheEnabled(), "cacheEnabled");
        assertEquals(2, neutered.getSubChunks().size(), "entry count");

        SubChunkData first = neutered.getSubChunks().get(0);
        assertEquals(SubChunkRequestResult.SUCCESS, first.getResult(), "result");
        assertEquals(HeightMapDataType.HAS_DATA, first.getHeightMapType(), "heightMapType");
        assertEquals(256, first.getHeightMapData().readableBytes(), "the heightmap must be untouched");
        assertEquals(HeightMapDataType.TOO_HIGH, neutered.getSubChunks().get(1).getHeightMapType());
    }

    @Test
    void minimalSubChunkModeCutsContentAndVolumeTogether() {
        SubChunkPacket neutered = neuteredSubChunk(NeuterMode.MINIMAL);

        for (SubChunkData entry : neutered.getSubChunks()) {
            assertEquals(2, entry.getData().readableBytes(), "MINIMAL cuts the payload to nothing");
        }
        assertTrue(encodedSize(neutered) < encodedSize(realisticSubChunk()),
                "MINIMAL is the control for SAME_SIZE, so it has to actually be smaller");
    }

    /**
     * The live shape, from the 2026-08-06 capture: caching off, a centre the client asked for, and a
     * mix of {@code HAS_DATA} and {@code TOO_HIGH} heightmaps with payloads in the 500-4000 byte band.
     */
    private static SubChunkPacket realisticSubChunk() {
        SubChunkPacket packet = new SubChunkPacket();
        packet.setDimension(0);
        packet.setCacheEnabled(false);
        packet.setCenterPosition(Vector3i.from(31, 0, 9));
        packet.getSubChunks().add(subChunkEntry(Vector3i.from(0, -4, 4), HeightMapDataType.HAS_DATA, 1685));
        packet.getSubChunks().add(subChunkEntry(Vector3i.from(0, -1, 4), HeightMapDataType.TOO_HIGH, 527));
        return packet;
    }

    private static SubChunkData subChunkEntry(Vector3i position, HeightMapDataType heightMapType, int payloadBytes) {
        SubChunkData entry = new SubChunkData();
        entry.setPosition(position);
        entry.setResult(SubChunkRequestResult.SUCCESS);
        entry.setData(Unpooled.buffer(payloadBytes, payloadBytes).writeZero(payloadBytes));
        entry.setHeightMapType(heightMapType);
        if (heightMapType == HeightMapDataType.HAS_DATA) {
            entry.setHeightMapData(Unpooled.buffer(256, 256).writeZero(256));
        }
        entry.setRenderHeightMapType(HeightMapDataType.NO_DATA);
        return entry;
    }

    private static SubChunkPacket neuteredSubChunk(NeuterMode mode) {
        SubChunkPacket packet = realisticSubChunk();
        BackendRelayPacketHandler.neuter(packet, mode);
        return packet;
    }

    /** Encoded length with the 1.26.40 client's own codec — the leg whose volume is in question. */
    private static int encodedSize(BedrockPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v2168.CODEC.tryEncode(Bedrock_v2168.CODEC.createHelper(), buffer, packet);
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }

    private static MoveEntityDeltaPacket roundTrip(BedrockCodec codec, MoveEntityDeltaPacket packet) {
        int id = codec.getPacketDefinition(MoveEntityDeltaPacket.class).getId();
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(), buffer, packet);
            return (MoveEntityDeltaPacket) codec.tryDecode(codec.createHelper(), buffer, id);
        } finally {
            buffer.release();
        }
    }
}
