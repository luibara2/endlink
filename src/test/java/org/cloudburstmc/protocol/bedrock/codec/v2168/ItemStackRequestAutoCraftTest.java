package org.cloudburstmc.protocol.bedrock.codec.v2168;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemDescriptorWithCount;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemTagDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.AutoCraftRecipeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A relayed auto-craft (recipe book / shift-click craft) has to leave the proxy byte-for-byte as the
 * client sent it. Two upstream defects meant it did not, and BDS answers a malformed
 * ItemStackRequest with a PacketViolationWarning at severity {@code TERMINATING_CONNECTION} — the
 * player is dropped, the proxy sees only {@code disconnect.timeout} and fails over.
 *
 * <p>The live report was {@code wrong const value for member "Descriptor Type"} /
 * {@code readNoHeader failed! packetId: 147} coming back from the backend, which is a desync landing
 * on the first ingredient rather than anything wrong with the descriptor itself.</p>
 *
 * <p>These use tag ingredients on purpose: a {@code DEFAULT} descriptor is written out of the item
 * definition registry, which a bare codec has not been given.</p>
 */
class ItemStackRequestAutoCraftTest {

    private static final int PACKET_ID =
            Bedrock_v2168.CODEC.getPacketDefinition(ItemStackRequestPacket.class).getId();

    /** CRAFT_RECIPE_AUTO's case index, from {@code Bedrock_v2168.ITEM_STACK_REQUEST_TYPES}. */
    private static final int CRAFT_RECIPE_AUTO_CASE_INDEX = 11;

    /** Its enum value as BDS counts them, two higher because the deprecated members still count. */
    private static final int CRAFT_RECIPE_AUTO_ENUM_VALUE = 13;

    private static final int ITEM_TAG_DESCRIPTOR = 3;

    @Test
    void autoCraftReEncodesByteForByte() {
        ByteBuf fromClient = clientAutoCraft();
        ByteBuf toDecode = fromClient.copy();
        ByteBuf reEncoded = Unpooled.buffer();
        try {
            ItemStackRequestPacket decoded = (ItemStackRequestPacket) Bedrock_v2168.CODEC.tryDecode(
                    Bedrock_v2168.CODEC.createHelper(), toDecode, PACKET_ID);
            assertEquals(0, toDecode.readableBytes(), "the reader must consume the whole packet");

            Bedrock_v2168.CODEC.tryEncode(Bedrock_v2168.CODEC.createHelper(), reEncoded, decoded);

            // Hex rather than ByteBuf equality so a failure shows where the two diverge.
            assertEquals(ByteBufUtil.hexDump(fromClient), ByteBufUtil.hexDump(reEncoded),
                    "a re-encoded auto-craft must be identical to what the client sent");
        } finally {
            fromClient.release();
            toDecode.release();
            reEncoded.release();
        }
    }

    @Test
    void theIngredientArrayCarriesExactlyOneLengthPrefix() {
        // The writer used to emit a byte count in front of writeArray's own unsigned varint. Below
        // 128 the two encode identically, so the far end reads the stray byte as the length, still
        // sees the right count, and then starts the first ingredient one byte early - on the real
        // length prefix. With two ingredients that makes the descriptor's union selector read 2
        // (MOLANG) while its repeated const byte reads 3, which is the const mismatch BDS reported.
        ByteBuf encoded = Unpooled.buffer();
        try {
            Bedrock_v2168.CODEC.tryEncode(Bedrock_v2168.CODEC.createHelper(), encoded, autoCraftPacket());

            assertEquals(clientAutoCraftLength(), encoded.readableBytes(),
                    "one byte too many means the stray length prefix is back");
        } finally {
            encoded.release();
        }
    }

    @Test
    void theActionCaseIndexAndEnumValueAreDeliberatelyDifferent() {
        // These two fields look like a discriminant written twice and are not. The varint is the
        // union's case index (no deprecated members); the byte is BDS's enum value (still counting
        // them), which BDS validates as the const member it calls "Action type". "Tidying" them into
        // agreement takes down every ItemStackRequest and every PlayerAuthInput carrying one - see the
        // note on writeRequestActionData. This asserts they differ so that cleanup cannot land twice.
        ByteBuf encoded = Unpooled.buffer();
        try {
            Bedrock_v2168.CODEC.tryEncode(Bedrock_v2168.CODEC.createHelper(), encoded, autoCraftPacket());

            assertEquals(1, encoded.getUnsignedByte(0), "one request");
            assertEquals(2, encoded.getUnsignedByte(1), "requestId 1, zigzagged");
            assertEquals(1, encoded.getUnsignedByte(2), "one action");

            assertEquals(CRAFT_RECIPE_AUTO_CASE_INDEX, encoded.getUnsignedByte(3),
                    "the varint is the TypeMap case index");
            assertEquals(CRAFT_RECIPE_AUTO_ENUM_VALUE, encoded.getUnsignedByte(4),
                    "the byte is the enum value BDS const-checks, which is the ordinal");
        } finally {
            encoded.release();
        }
    }

    @Test
    void autoCraftIngredientsSurviveTheTrip() {
        ByteBuf toDecode = clientAutoCraft();
        try {
            ItemStackRequestPacket decoded = (ItemStackRequestPacket) Bedrock_v2168.CODEC.tryDecode(
                    Bedrock_v2168.CODEC.createHelper(), toDecode, PACKET_ID);

            assertEquals(1, decoded.getRequests().size());
            ItemStackRequest request = decoded.getRequests().get(0);
            assertEquals(1, request.getRequestId());

            AutoCraftRecipeAction action =
                    assertInstanceOf(AutoCraftRecipeAction.class, request.getActions()[0]);
            assertEquals(7, action.getRecipeNetworkId());
            assertEquals(3, action.getNumberOfRequestedCrafts());

            List<ItemDescriptorWithCount> ingredients = action.getIngredients();
            assertEquals(2, ingredients.size());
            assertEquals("minecraft:planks",
                    assertInstanceOf(ItemTagDescriptor.class, ingredients.get(0).getDescriptor()).getItemTag());
            assertEquals(4, ingredients.get(0).getCount());
            assertEquals("minecraft:stick",
                    assertInstanceOf(ItemTagDescriptor.class, ingredients.get(1).getDescriptor()).getItemTag());
            assertEquals(1, ingredients.get(1).getCount());
        } finally {
            toDecode.release();
        }
    }

    /** The payload a 1.26.40 client sends for a two-tag-ingredient recipe crafted three times. */
    private static ByteBuf clientAutoCraft() {
        ByteBuf buf = Unpooled.buffer();
        VarInts.writeUnsignedInt(buf, 1);                   // one request
        VarInts.writeInt(buf, 1);                           // requestId
        VarInts.writeUnsignedInt(buf, 1);                   // one action
        VarInts.writeUnsignedInt(buf, CRAFT_RECIPE_AUTO_CASE_INDEX);
        buf.writeByte(CRAFT_RECIPE_AUTO_ENUM_VALUE);        // the const BDS calls "Action type"
        VarInts.writeUnsignedInt(buf, 7);                   // recipeNetworkId
        buf.writeByte(3);                                   // numberOfRequestedCrafts
        VarInts.writeUnsignedInt(buf, 2);                   // the ingredient array's only length prefix
        writeTagIngredient(buf, "minecraft:planks", 4);
        writeTagIngredient(buf, "minecraft:stick", 1);
        VarInts.writeUnsignedInt(buf, 0);                   // no filter strings
        buf.writeIntLE(-1);                                 // no TextProcessingEventOrigin
        return buf;
    }

    private static int clientAutoCraftLength() {
        ByteBuf buf = clientAutoCraft();
        try {
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }

    private static void writeTagIngredient(ByteBuf buf, String tag, int count) {
        VarInts.writeUnsignedInt(buf, ITEM_TAG_DESCRIPTOR);
        buf.writeByte(ITEM_TAG_DESCRIPTOR);                 // the const BDS names "Descriptor Type"
        byte[] utf8 = tag.getBytes(StandardCharsets.UTF_8);
        VarInts.writeUnsignedInt(buf, utf8.length);
        buf.writeBytes(utf8);
        buf.writeShortLE(count);
    }

    private static ItemStackRequestPacket autoCraftPacket() {
        List<ItemDescriptorWithCount> ingredients = Arrays.asList(
                new ItemDescriptorWithCount(new ItemTagDescriptor("minecraft:planks"), 4),
                new ItemDescriptorWithCount(new ItemTagDescriptor("minecraft:stick"), 1));

        ItemStackRequestPacket packet = new ItemStackRequestPacket();
        packet.getRequests().add(new ItemStackRequest(
                1,
                new ItemStackRequestAction[]{new AutoCraftRecipeAction(7, 3, ingredients, 3)},
                new String[0],
                null));
        return packet;
    }
}
