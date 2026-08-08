package org.endstone.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps {@link CrossProtocolPacketSweepTest} honest.
 *
 * <p>That sweep skips any packet it cannot round-trip through its own codec, which is the right
 * behaviour but makes a green result ambiguous: a packet that is never exercised passes exactly like
 * a packet that works. This test names the packets that gate a join and fails if any of them is
 * being skipped, so "the sweep is green" cannot quietly mean "the sweep tested nothing that
 * matters".</p>
 */
class CrossProtocolCoverageTest {

    /** If a player does not spawn, the cause is almost certainly one of these. */
    private static final List<String> JOIN_CRITICAL = List.of(
            "StartGamePacket",
            "ResourcePacksInfoPacket",
            "ResourcePackStackPacket",
            "ResourcePackClientResponsePacket",
            "PlayStatusPacket",
            "SetLocalPlayerAsInitializedPacket",
            "RequestChunkRadiusPacket",
            "ChunkRadiusUpdatedPacket",
            "NetworkChunkPublisherUpdatePacket",
            "LevelChunkPacket",
            "SetEntityDataPacket",
            "AddPlayerPacket",
            "PlayerListPacket",
            "SetSpawnPositionPacket",
            "RespawnPacket",
            "MovePlayerPacket",
            "PlayerAuthInputPacket"
    );

    @Test
    void theJoinCriticalPacketsAreActuallyExercisedInBothDirections() {
        List<String> notCovered = new ArrayList<>();

        for (String name : JOIN_CRITICAL) {
            String legacy = roundTripFailure(Bedrock_v1001.CODEC, name);
            if (legacy != null) {
                notCovered.add(name + " on 1.26.30 — " + legacy);
            }
            String modern = roundTripFailure(Bedrock_v2168.CODEC, name);
            if (modern != null) {
                notCovered.add(name + " on 1.26.40 — " + modern);
            }
        }

        if (!notCovered.isEmpty()) {
            throw new AssertionError("""
                    These packets gate a join and the cross-protocol sweep is not testing them, so its \
                    green result says nothing about them. Teach PacketPopulator how to build them:
                      """ + String.join("\n  ", notCovered));
        }
    }

    /** Null when the packet round-trips; otherwise why it does not, so the fix is obvious. */
    private static String roundTripFailure(BedrockCodec codec, String packetName) {
        for (int id = 0; id < 512; id++) {
            BedrockPacketDefinition<?> definition = codec.getPacketDefinition(id);
            if (definition == null) {
                continue;
            }
            BedrockPacket packet = definition.getFactory().get();
            if (!packet.getClass().getSimpleName().equals(packetName)) {
                continue;
            }
            ByteBuf buffer = Unpooled.buffer();
            try {
                codec.tryEncode(helperFor(codec), buffer, PacketPopulator.populate(packet));
                codec.tryDecode(helperFor(codec), buffer, id);
                return null;
            } catch (Throwable failure) {
                Throwable cause = failure;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                return cause.getClass().getSimpleName() + ": " + cause.getMessage();
            } finally {
                buffer.release();
            }
        }
        return "not registered in this codec";
    }

    /**
     * The live proxy installs both registries before any packet is decoded, and serializers that
     * carry a block or item reference dereference them without a null check. A bare
     * {@code createHelper()} has neither, so any such packet fails here for a reason the real
     * pipeline never sees — {@code PlayerAuthInput} did once its {@code ItemUseTransaction} started
     * being written. Accepting every id keeps the stub out of the way of what is under test.
     */
    private static BedrockCodecHelper helperFor(BedrockCodec codec) {
        BedrockCodecHelper helper = codec.createHelper();
        helper.setBlockDefinitions(new DefinitionRegistry<>() {
            @Override
            public BlockDefinition getDefinition(int runtimeId) {
                return new SimpleBlockDefinition("minecraft:air", runtimeId, NbtMap.EMPTY);
            }

            @Override
            public boolean isRegistered(BlockDefinition definition) {
                return definition != null;
            }
        });
        helper.setItemDefinitions(new DefinitionRegistry<>() {
            @Override
            public ItemDefinition getDefinition(int runtimeId) {
                return new SimpleItemDefinition("minecraft:air", runtimeId, false);
            }

            @Override
            public boolean isRegistered(ItemDefinition definition) {
                return definition != null;
            }
        });
        return helper;
    }
}
