package org.cloudburstmc.protocol.bedrock.codec.v2168;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The player-list repair, against the payloads that actually caused disconnects.
 *
 * <p>PlayerList is a broadcast: one malformed entry from a backend disconnects everyone on it. These
 * three bodies were captured from the live proxy log — two from the {@code endstone-playerlist-guard}
 * plugin's own suite, one from this proxy's {@code lobby} backend — and all three are the same BDS
 * truncation, ending immediately after {@code platformChatId}.</p>
 */
class PlayerListGuardTest {

    private static final int PACKET_ID =
            Bedrock_v2168.CODEC.getPacketDefinition(PlayerListPacket.class).getId();

    /** From this proxy's log: {@code Unknown backend packet from lobby: id=63 payloadBytes=39}. */
    private static final String CAPTURED_LOBBY_LIST =
            "020100e637b3115e74abd36e8875dcb8af23b8e3ffffffdf010b6c7569206e6574776f726b0000";

    /** The plugin suite's two, kept so both implementations are pinned to the same bytes. */
    private static final String CAPTURED_SKYGEN =
            "010100a637f6c94e8bd27268ec8c464a1bf78" + "4e998ffffbfa501"
                    + "06666176656b31" + "1032353335343131323331393030383430" + "00";
    private static final String CAPTURED_LOBBY =
            "010100a637f6c94e8bd27268ec8c464a1bf78" + "4dfffffffbf01"
                    + "06666176656b31" + "0000";

    @Test
    void theCapturedLobbyBroadcastDecodesInsteadOfDisconnectingEveryone() {
        PlayerListPacket decoded = decode(CAPTURED_LOBBY_LIST);

        // The truncated ADD is completed rather than dropped, so the identity still reaches the tab
        // list. Its declared count of 2 was a lie - only one entry's bytes were ever sent.
        assertEquals(1, decoded.getEntries().size());
        PlayerListPacket.Entry entry = decoded.getEntries().get(0);
        assertEquals("lui network", entry.getName());
        assertEquals(PlayerListPacket.Action.ADD, entry.getAction());
        assertNotNull(entry.getSkin(), "the repair supplies a skin, which is the field BDS omitted");
        assertTrue(entry.getSkin().getSkinData().getWidth() == 64
                && entry.getSkin().getSkinData().getHeight() == 64, "a full 64x64 Steve texture");
    }

    @Test
    void theRepairedPacketReEncodesAndReadsBackCleanly() {
        // The point of the exercise: what leaves the proxy has to be something a client can parse.
        PlayerListPacket decoded = decode(CAPTURED_LOBBY_LIST);

        ByteBuf reEncoded = Unpooled.buffer();
        try {
            Bedrock_v2168.CODEC.tryEncode(Bedrock_v2168.CODEC.createHelper(), reEncoded, decoded);
            PlayerListPacket again = (PlayerListPacket) Bedrock_v2168.CODEC.tryDecode(
                    Bedrock_v2168.CODEC.createHelper(), reEncoded, PACKET_ID);
            assertEquals(0, reEncoded.readableBytes(), "the client must consume the whole packet");
            assertEquals(1, again.getEntries().size());
            assertEquals("lui network", again.getEntries().get(0).getName());
        } finally {
            reEncoded.release();
        }
    }

    @Test
    void bothPluginCapturedPayloadsAreRepairedToo() {
        for (String hex : new String[]{CAPTURED_SKYGEN, CAPTURED_LOBBY}) {
            PlayerListPacket decoded = decode(hex);
            assertEquals(1, decoded.getEntries().size(), hex);
            assertEquals("favek1", decoded.getEntries().get(0).getName(), hex);
            assertNotNull(decoded.getEntries().get(0).getSkin(), hex);
        }
    }

    @Test
    void aLegacyRemovalIsUpgradedRatherThanReadAsAnAdd() {
        // REMOVE | count | uuid... - what plugins written against the pre-1.26.40 layout still send.
        // A 1.26.40 client reads those bytes as an ADD and disconnects hunting for a skin.
        UUID first = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID second = UUID.fromString("00000000-0000-4000-8000-000000000002");

        ByteBuf legacy = Unpooled.buffer();
        legacy.writeByte(1);                    // legacy packet-level REMOVE
        legacy.writeByte(2);                    // count
        writeUuid(legacy, first);
        writeUuid(legacy, second);

        PlayerListPacket decoded = decode(ByteBufUtil.hexDump(legacy));
        legacy.release();

        assertEquals(2, decoded.getEntries().size());
        assertEquals(PlayerListPacket.Action.REMOVE, decoded.getEntries().get(0).getAction());
        assertEquals(first, decoded.getEntries().get(0).getUuid());
        assertEquals(second, decoded.getEntries().get(1).getUuid());
    }

    @Test
    void theVanishHideRemovalIsUpgraded() {
        // The single-uuid form of the same thing, and the one seen in production: endstone-skygen-vanish
        // hides a player by sending each viewer RemoveActor and then this, back to back. That pair is
        // the last thing a disconnecting client logs - RemoveActor is handled, and the client dies on
        // the player list that follows it. Covered separately from the two-uuid case above because it
        // is the exact payload, and because count and body length agreeing is what makes the upgrade
        // safe to attempt at all.
        UUID vanished = UUID.fromString("00000000-0000-4000-8000-00000000000a");

        ByteBuf legacy = Unpooled.buffer();
        legacy.writeByte(1);                    // legacy packet-level REMOVE
        legacy.writeByte(1);                    // count
        writeUuid(legacy, vanished);

        PlayerListPacket decoded = decode(ByteBufUtil.hexDump(legacy));
        legacy.release();

        assertEquals(1, decoded.getEntries().size());
        assertEquals(PlayerListPacket.Action.REMOVE, decoded.getEntries().get(0).getAction());
        assertEquals(vanished, decoded.getEntries().get(0).getUuid());
    }

    @Test
    void aValidPacketIsLeftExactlyAlone() {
        // The guard runs on every player list, so the common path must be byte-for-byte untouched.
        UUID uuid = UUID.fromString("00000000-0000-4000-8000-0000000000ff");
        ByteBuf valid = Unpooled.buffer();
        valid.writeByte(1);                     // one entry
        valid.writeByte(0);                     // case index: RemoveEntry
        valid.writeByte(1);                     // the const BDS calls "Action"
        writeUuid(valid, uuid);
        String before = ByteBufUtil.hexDump(valid);

        PlayerListPacket decoded = decode(before);
        assertEquals(1, decoded.getEntries().size());
        assertEquals(PlayerListPacket.Action.REMOVE, decoded.getEntries().get(0).getAction());

        ByteBuf reEncoded = Unpooled.buffer();
        try {
            Bedrock_v2168.CODEC.tryEncode(Bedrock_v2168.CODEC.createHelper(), reEncoded, decoded);
            assertEquals(before, ByteBufUtil.hexDump(reEncoded));
        } finally {
            valid.release();
            reEncoded.release();
        }
    }

    @Test
    void anUnrepairableBodyDegradesToAnEmptyListRatherThanThrowing() {
        // Not the known truncation - random bytes where an entry should be. Dropping the entry keeps
        // the packet structurally valid, which is the whole requirement.
        PlayerListPacket decoded = decode("01" + "01" + "00" + "deadbeef");
        assertEquals(0, decoded.getEntries().size());
    }

    @Test
    void completingAnAddPlayerBuildPlatformIsIdempotent() {
        byte[] truncated = new byte[20];
        byte[] once = PlayerListGuard.completeAddPlayerBuildPlatform(truncated);
        assertEquals(truncated.length + 4, once.length);
        assertArrayEquals(PlayerListGuard.UNKNOWN_BUILD_PLATFORM,
                java.util.Arrays.copyOfRange(once, once.length - 4, once.length));

        byte[] twice = PlayerListGuard.completeAddPlayerBuildPlatform(once);
        assertArrayEquals(once, twice, "a body already ending in a known platform is untouched");
    }

    @Test
    void theEmbeddedAssetsAreTheRealOnes() {
        // A corrupted blob would produce a skin the client rejects, which is the failure this whole
        // class exists to avoid, so check them rather than trust the base64.
        byte[] tail = PlayerListGuard.defaultSteveAddTail(new byte[16]);
        assertTrue(tail.length > 64 * 64 * 4, "the tail must carry a full 64x64 RGBA texture");
        assertFalse(new String(tail, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("geometry.humanoid.custom "), "geometry must not be truncated");
    }

    @Test
    void aSkinLargerThan256By256SurvivesTheRoundTrip() {
        // A 512x256 marketplace skin: 524288 bytes, twice the upstream cap. It used to drop the
        // player's entry as unrepairable, so everyone else lost them from the list.
        byte[] pixels = new byte[512 * 256 * 4];
        pixels[pixels.length - 1] = 7;
        PlayerListPacket packet = new PlayerListPacket();
        packet.setAction(PlayerListPacket.Action.ADD);
        PlayerListPacket.Entry entry = new PlayerListPacket.Entry(UUID.randomUUID());
        entry.setAction(PlayerListPacket.Action.ADD);
        entry.setEntityId(1);
        entry.setName("hotdog");
        entry.setXuid("");
        entry.setPlatformChatId("");
        entry.setColor(new java.awt.Color(0, true));
        entry.setSkin(org.cloudburstmc.protocol.bedrock.data.skin.SerializedSkin.builder()
                .skinId("big")
                .skinResourcePatch("{\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}")
                .skinData(org.cloudburstmc.protocol.bedrock.data.skin.ImageData.of(512, 256, pixels))
                .geometryData("")
                .build());
        packet.getEntries().add(entry);
        ByteBuf encoded = Unpooled.buffer();
        try {
            Bedrock_v2168.CODEC.tryEncode(Bedrock_v2168.CODEC.createHelper(), encoded, packet);
            PlayerListPacket decoded = (PlayerListPacket) Bedrock_v2168.CODEC.tryDecode(
                    Bedrock_v2168.CODEC.createHelper(), encoded, PACKET_ID);
            assertEquals(1, decoded.getEntries().size(), "the entry is kept");
            assertEquals(512, decoded.getEntries().get(0).getSkin().getSkinData().getWidth());
            assertArrayEquals(pixels, decoded.getEntries().get(0).getSkin().getSkinData().getImage());
        } finally {
            encoded.release();
        }
    }

    private static PlayerListPacket decode(String hex) {
        ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(hex));
        try {
            PlayerListPacket packet = (PlayerListPacket) Bedrock_v2168.CODEC.tryDecode(
                    Bedrock_v2168.CODEC.createHelper(), buffer, PACKET_ID);
            assertEquals(0, buffer.readableBytes(), "the guard must consume the whole body");
            return packet;
        } finally {
            buffer.release();
        }
    }

    private static void writeUuid(ByteBuf buffer, UUID uuid) {
        buffer.writeLongLE(uuid.getMostSignificantBits());
        buffer.writeLongLE(uuid.getLeastSignificantBits());
    }
}
