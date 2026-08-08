package org.endstone.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.junit.jupiter.api.Test;

import static org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket.Flag.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MoveEntityDeltaPacket} across the 1.26.40 &harr; 1.26.30 hop.
 *
 * <p>This is the highest-volume clientbound packet there is — around 170 a second in a live session,
 * and 60-80% of everything the proxy relays. 1.26.40 moved {@code ON_GROUND} and the three force
 * flags out of the packed 16-bit flag word into four trailing booleans, and the two halves of the
 * model are populated by different serializers: everything up to 1.26.30 records them only in
 * {@code getFlags()}, 1.26.40 only in the boolean fields.</p>
 *
 * <p>Nothing throws when they disagree — the packet just quietly says every entity is airborne. The
 * receiving client then runs its own physics for every entity it believes unsupported, so the whole
 * world is permanently falling. The per-codec asymmetry check cannot see this, because each codec
 * round-trips itself perfectly; only the hop exposes it.</p>
 */
class CrossProtocolMoveEntityDeltaTest {

    @Test
    void onGroundSurvivesFromA1_26_30Backend() {
        MoveEntityDeltaPacket packet = new MoveEntityDeltaPacket();
        packet.setRuntimeEntityId(42L);
        packet.setX(1f);
        packet.setY(70f);
        packet.setZ(3f);
        // How a pre-1.26.40 serializer expresses it: membership of the flag set, booleans untouched.
        packet.getFlags().add(HAS_X);
        packet.getFlags().add(HAS_Y);
        packet.getFlags().add(HAS_Z);
        packet.getFlags().add(ON_GROUND);

        MoveEntityDeltaPacket relayed = hop(Bedrock_v1001.CODEC, Bedrock_v2168.CODEC, packet);

        assertTrue(relayed.isOnGround(),
                "a grounded entity must not arrive at a 1.26.40 client as airborne");
        assertEquals(70f, relayed.getY(), 0.001);
        assertEquals(42L, relayed.getRuntimeEntityId());
    }

    @Test
    void theForceFlagsSurviveFromA1_26_30Backend() {
        MoveEntityDeltaPacket packet = new MoveEntityDeltaPacket();
        packet.setRuntimeEntityId(7L);
        packet.getFlags().add(TELEPORTING);
        packet.getFlags().add(FORCE_MOVE_LOCAL_ENTITY);
        packet.getFlags().add(FORCE_COMPLETION);

        MoveEntityDeltaPacket relayed = hop(Bedrock_v1001.CODEC, Bedrock_v2168.CODEC, packet);

        assertTrue(relayed.isForceMove(), "TELEPORTING must reach the client as forceMove");
    }

    /**
     * The decisive one, built from the bytes a vanilla 1.26.30 server actually puts on the wire
     * rather than from a hand-populated packet.
     *
     * <p>The flag word is preset to {@code 0xFFFF} and bits are <em>cleared</em> for flags that are
     * not set, so every bit the sending version has no flag for arrives as a 1. Vanilla has nine
     * flags, bits 0-8. Bit 9 is not a flag — it is leftover preset — and 1.26.40 is the version that
     * gave ordinal 9 a meaning ({@code FORCE_COMPLETION}).
     *
     * <p>So a plain "entity moved and is on the ground" packet, relayed to a 1.26.40 client, used to
     * arrive asserting a force-completion the server never sent. Around 90 times a second, on every
     * entity in view.
     *
     * <p>A hand-populated packet cannot catch this: it only appears when the flag word is built the
     * way vanilla builds it, which is why the bytes are written out by hand here.
     */
    @Test
    void theZeroFfffPresetDoesNotInventFlagsForA1_26_40Client() {
        // Nine known flags, all cleared except HAS_X/Y/Z and ON_GROUND; bits 9-15 left at the preset.
        int flags = 0xFFFF;
        for (int bit : new int[]{HAS_PITCH.ordinal(), HAS_YAW.ordinal(), HAS_HEAD_YAW.ordinal(),
                TELEPORTING.ordinal(), FORCE_MOVE_LOCAL_ENTITY.ordinal()}) {
            flags &= ~(1 << bit);
        }

        ByteBuf wire = Unpooled.buffer();
        MoveEntityDeltaPacket relayed;
        try {
            wire.writeByte(42);              // runtime entity id, varint
            wire.writeShortLE(flags);
            wire.writeFloatLE(1f);
            wire.writeFloatLE(70f);
            wire.writeFloatLE(3f);

            int id = Bedrock_v1001.CODEC.getPacketDefinition(MoveEntityDeltaPacket.class).getId();
            MoveEntityDeltaPacket fromBackend = (MoveEntityDeltaPacket) Bedrock_v1001.CODEC
                    .tryDecode(Bedrock_v1001.CODEC.createHelper(), wire, id);
            assertEquals(0, wire.readableBytes(), "the 1.26.30 reader must consume exactly these bytes");
            relayed = roundTrip(Bedrock_v2168.CODEC, fromBackend);
        } finally {
            wire.release();
        }

        assertTrue(relayed.isOnGround(), "ON_GROUND was set and must survive");
        assertEquals(70f, relayed.getY(), 0.001);
        assertFalse(relayed.isForceCompletion(),
                "bit 9 is the untouched 0xFFFF preset on a 1.26.30 wire, not a force-completion");
        assertFalse(relayed.isForceMoveLocalEntity(),
                "1.26.30 has no force-move-local-entity bit; asserting it moves the player's own entity");
        assertFalse(relayed.isForceMove(),
                "neither Teleport nor ForceMove was set, so the client must not be told to force the move");
    }

    /**
     * And the other way: a packet decoded from 1.26.40 has to carry the flags an older serializer
     * reads, or the same loss happens in reverse the moment one is relayed downward.
     */
    @Test
    void a1_26_40PacketCarriesTheFlagsAnOlderSerializerReads() {
        MoveEntityDeltaPacket packet = new MoveEntityDeltaPacket();
        packet.setRuntimeEntityId(9L);
        packet.setOnGround(true);
        packet.setForceMove(true);

        MoveEntityDeltaPacket decoded = roundTrip(Bedrock_v2168.CODEC, packet);

        assertTrue(decoded.getFlags().contains(ON_GROUND), "flag set must mirror the boolean");
        assertTrue(decoded.getFlags().contains(TELEPORTING));
        assertTrue(decoded.isOnGround());
    }

    private static MoveEntityDeltaPacket hop(BedrockCodec from, BedrockCodec to, MoveEntityDeltaPacket packet) {
        return roundTrip(to, roundTrip(from, packet));
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
