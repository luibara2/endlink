package org.endstone.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.ScoreInfo;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.skin.PersonaPieceData;
import org.cloudburstmc.protocol.bedrock.data.skin.PersonaPieceTintData;
import org.cloudburstmc.protocol.bedrock.data.skin.PersonaPieceType;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetScorePacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The companion to {@link CrossProtocolPacketSweepTest}, and the reason it is not enough on its own.
 *
 * <p>That sweep builds every packet from its default factory, which means every list on every packet
 * is empty. Packets whose whole shape lives inside a repeated element therefore encode and decode
 * without touching the code that matters. {@code PlayerListPacket} is the case that proves it: the
 * sweep passed it clientbound while a player list with even one entry would have killed the relay,
 * because 1.26.40 moved the add/remove action out of the packet and onto each entry, and
 * {@code PlayerListSerializer_v390.deserialize} — the 1.26.30 reader — only ever sets the packet-level
 * one.</p>
 *
 * <p>So these tests carry real contents across the hop.</p>
 */
class CrossProtocolPopulatedPacketTest {

    @Test
    void aPlayerListFromA1_26_30BackendSurvivesTheHopToA1_26_40Client() {
        PlayerListPacket packet = new PlayerListPacket();
        packet.setAction(PlayerListPacket.Action.REMOVE);
        packet.getEntries().add(new PlayerListPacket.Entry(UUID.randomUUID()));
        packet.getEntries().add(new PlayerListPacket.Entry(UUID.randomUUID()));

        PlayerListPacket relayed = hop(Bedrock_v1001.CODEC, Bedrock_v2168.CODEC, packet);

        assertEquals(2, relayed.getEntries().size());
        // The action has to arrive on every entry, whichever side set it.
        for (PlayerListPacket.Entry entry : relayed.getEntries()) {
            assertEquals(PlayerListPacket.Action.REMOVE, entry.getAction());
        }
    }

    /**
     * The same packet built by the proxy rather than decoded from a backend — the REMOVE that
     * {@code ClientWorldState} sends on a backend switch. It sets only the packet-level action, so
     * this fails at matching versions too if the entry action is treated as mandatory.
     */
    @Test
    void aProxyBuiltPlayerListRemovalEncodesForA1_26_40Client() {
        PlayerListPacket packet = new PlayerListPacket();
        packet.setAction(PlayerListPacket.Action.REMOVE);
        packet.getEntries().add(new PlayerListPacket.Entry(UUID.randomUUID()));

        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v2168.CODEC.tryEncode(Bedrock_v2168.CODEC.createHelper(), buffer, packet);
            assertEquals(true, buffer.readableBytes() > 0);
        } finally {
            buffer.release();
        }
    }

    @Test
    void aScoreboardUpdateFromA1_26_30BackendSurvivesTheHop() {
        SetScorePacket packet = new SetScorePacket();
        packet.setAction(SetScorePacket.Action.SET);
        packet.getInfos().add(new ScoreInfo(1L, "objective", 7, "FakePlayer"));
        packet.getInfos().add(new ScoreInfo(2L, "objective", 9, ScoreInfo.ScorerType.PLAYER, 42L));

        SetScorePacket relayed = hop(Bedrock_v1001.CODEC, Bedrock_v2168.CODEC, packet);

        assertEquals(2, relayed.getInfos().size());
        assertEquals("FakePlayer", relayed.getInfos().get(0).getName());
        assertEquals(9, relayed.getInfos().get(1).getScore());
    }

    /**
     * Entity metadata is the highest-volume clientbound payload there is, and 1.26.40 retyped index
     * 16 and added index 25. The type maps resolve ids to constants on both sides, so this is really
     * a check that the two maps still agree on the constants themselves.
     */
    @Test
    void entityMetadataFromA1_26_30BackendSurvivesTheHop() {
        SetEntityDataPacket packet = new SetEntityDataPacket();
        packet.setRuntimeEntityId(7L);
        packet.getMetadata().put(EntityDataTypes.SCALE, 1.5f);
        packet.getMetadata().put(EntityDataTypes.NAME, "Steve");
        packet.getMetadata().put(EntityDataTypes.AIR_SUPPLY, (short) 300);

        SetEntityDataPacket relayed = hop(Bedrock_v1001.CODEC, Bedrock_v2168.CODEC, packet);

        assertEquals(7L, relayed.getRuntimeEntityId());
        assertEquals(1.5f, relayed.getMetadata().get(EntityDataTypes.SCALE));
        assertEquals("Steve", relayed.getMetadata().get(EntityDataTypes.NAME));
    }

    /**
     * 1.26.40 typed the persona piece fields — the piece type became an int enum and the pack id a
     * real UUID — while every earlier version sends free text. Decoding that text must never throw,
     * because a failed skin fails the packet carrying it, and a failed packet gets raw-forwarded,
     * which is exactly the thing that cannot happen across versions.
     */
    @Test
    void aPersonaSkinWithUnrecognisedPiecesStillDecodes() {
        // A piece type this build has never heard of, and a pack id that is not a UUID. Both are
        // things a real backend can send, and both used to throw.
        PersonaPieceData piece = new PersonaPieceData("piece-id", "some_future_piece", "", false, "product");

        // The original text survives, so a 1.26.30 peer gets back exactly what it sent...
        assertEquals("some_future_piece", piece.getType());
        assertEquals("", piece.getPackId());
        // ...while the typed view a 1.26.40 peer needs still resolves to something encodable.
        assertEquals(PersonaPieceType.UNKNOWN, piece.getPieceType());
        assertNotNull(piece.getPackUuid());

        PersonaPieceTintData tint = new PersonaPieceTintData("some_future_piece", List.of("#ff0000"));
        assertEquals("some_future_piece", tint.getType());
        assertEquals(1, tint.getColorsNew().size());
    }

    /**
     * Encodes with the sending side's codec, decodes it there, re-encodes with the receiving side's
     * and decodes again — the full path a relayed packet takes through the proxy.
     */
    @SuppressWarnings("unchecked")
    private static <T extends BedrockPacket> T hop(BedrockCodec from, BedrockCodec to, T packet) {
        int id = from.getPacketDefinition((Class<T>) packet.getClass()).getId();

        ByteBuf sent = Unpooled.buffer();
        ByteBuf relayed = Unpooled.buffer();
        try {
            from.tryEncode(from.createHelper(), sent, packet);
            BedrockPacket decoded = from.tryDecode(from.createHelper(), sent, id);
            assertNotNull(decoded);

            to.tryEncode(to.createHelper(), relayed, decoded);
            return (T) to.tryDecode(to.createHelper(), relayed, id);
        } finally {
            sent.release();
            relayed.release();
        }
    }
}
