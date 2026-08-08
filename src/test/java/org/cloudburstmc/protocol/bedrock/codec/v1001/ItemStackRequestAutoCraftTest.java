package org.cloudburstmc.protocol.bedrock.codec.v1001;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemDescriptorWithCount;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemTagDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.AutoCraftRecipeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The same auto-craft defect as {@code v2168.ItemStackRequestAutoCraftTest}, on the other codec that
 * carries it: {@code BedrockCodecHelper_v712} wrote a byte-sized ingredient count and then called the
 * {@code writeArray} overload that prefixes an unsigned varint of its own, while its reader consumes
 * only the byte. v818 through v1001 inherit that method unchanged, so 1.26.30 backends were hit too.
 *
 * <p>Round-tripping through this codec is the whole check: with the extra prefix present the reader
 * takes the stray byte as the length and starts the first ingredient on the real one.</p>
 */
class ItemStackRequestAutoCraftTest {

    private static final int PACKET_ID =
            Bedrock_v1001.CODEC.getPacketDefinition(ItemStackRequestPacket.class).getId();

    @Test
    void autoCraftIngredientsSurviveARoundTrip() {
        List<ItemDescriptorWithCount> ingredients = Arrays.asList(
                new ItemDescriptorWithCount(new ItemTagDescriptor("minecraft:planks"), 4),
                new ItemDescriptorWithCount(new ItemTagDescriptor("minecraft:stick"), 1));

        ItemStackRequestPacket packet = new ItemStackRequestPacket();
        packet.getRequests().add(new ItemStackRequest(
                1,
                new ItemStackRequestAction[]{new AutoCraftRecipeAction(7, 2, ingredients, 3)},
                new String[0],
                null));

        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v1001.CODEC.tryEncode(Bedrock_v1001.CODEC.createHelper(), buffer, packet);
            ItemStackRequestPacket decoded = (ItemStackRequestPacket) Bedrock_v1001.CODEC.tryDecode(
                    Bedrock_v1001.CODEC.createHelper(), buffer, PACKET_ID);
            assertEquals(0, buffer.readableBytes(), "the reader must consume the whole packet");

            AutoCraftRecipeAction action = assertInstanceOf(AutoCraftRecipeAction.class,
                    decoded.getRequests().get(0).getActions()[0]);
            assertEquals(7, action.getRecipeNetworkId());
            assertEquals(2, action.getTimesCrafted());
            assertEquals(3, action.getNumberOfRequestedCrafts());

            List<ItemDescriptorWithCount> got = action.getIngredients();
            assertEquals(2, got.size());
            assertEquals("minecraft:planks",
                    assertInstanceOf(ItemTagDescriptor.class, got.get(0).getDescriptor()).getItemTag());
            assertEquals(4, got.get(0).getCount());
            assertEquals("minecraft:stick",
                    assertInstanceOf(ItemTagDescriptor.class, got.get(1).getDescriptor()).getItemTag());
            assertEquals(1, got.get(1).getCount());
        } finally {
            buffer.release();
        }
    }
}
