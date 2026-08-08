package org.endstone.proxy.codec;

import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraPreset;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.packet.CameraPresetsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.common.NamedDefinition;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;

public final class CodecDefinitionState {
    private static final DefinitionRegistry<BlockDefinition> UNKNOWN_BLOCKS = new UnknownBlockDefinitionRegistry();
    private static final DefinitionRegistry<ItemDefinition> UNKNOWN_ITEMS = new UnknownItemDefinitionRegistry();

    private CodecDefinitionState() {
    }

    public static void installFallbacks(BedrockSession session) {
        session.getPeer().getCodecHelper().setBlockDefinitions(UNKNOWN_BLOCKS);
        session.getPeer().getCodecHelper().setItemDefinitions(UNKNOWN_ITEMS);
    }

    public static void syncFromStartGame(BedrockSession backend, BedrockSession client, StartGamePacket packet) {
        if (!packet.getItemDefinitions().isEmpty()) {
            syncItemDefinitions(backend, client, packet.getItemDefinitions());
        }
    }

    public static void syncFromItemComponents(BedrockSession backend, BedrockSession client, ItemComponentPacket packet) {
        syncItemDefinitions(backend, client, packet.getItems());
    }

    public static void syncFromCameraPresets(BedrockSession backend, BedrockSession client, CameraPresetsPacket packet) {
        SimpleDefinitionRegistry.Builder<NamedDefinition> builder = SimpleDefinitionRegistry.builder();
        for (int i = 0; i < packet.getPresets().size(); i++) {
            builder.add(CameraPresetDefinition.from(packet.getPresets().get(i), i));
        }

        DefinitionRegistry<NamedDefinition> definitions = builder.build();
        backend.getPeer().getCodecHelper().setCameraPresetDefinitions(definitions);
        client.getPeer().getCodecHelper().setCameraPresetDefinitions(definitions);
    }

    private static void syncItemDefinitions(
            BedrockSession backend,
            BedrockSession client,
            Iterable<? extends ItemDefinition> itemDefinitions
    ) {
        SimpleDefinitionRegistry.Builder<ItemDefinition> builder = SimpleDefinitionRegistry.builder();
        builder.add(ItemDefinition.AIR);
        builder.add(new SimpleItemDefinition("minecraft:empty", 0, false));
        for (ItemDefinition definition : itemDefinitions) {
            builder.add(definition);
        }

        DefinitionRegistry<ItemDefinition> definitions = builder.build();
        backend.getPeer().getCodecHelper().setItemDefinitions(definitions);
        client.getPeer().getCodecHelper().setItemDefinitions(definitions);
    }

    public static DefinitionRegistry<BlockDefinition> unknownBlockDefinitions() {
        return UNKNOWN_BLOCKS;
    }

    public static DefinitionRegistry<ItemDefinition> unknownItemDefinitions() {
        return UNKNOWN_ITEMS;
    }

    private record UnknownBlockDefinition(int runtimeId) implements BlockDefinition {
        @Override
        public int getRuntimeId() {
            return runtimeId;
        }
    }

    private static final class UnknownBlockDefinitionRegistry implements DefinitionRegistry<BlockDefinition> {
        @Override
        public BlockDefinition getDefinition(int runtimeId) {
            return new UnknownBlockDefinition(runtimeId);
        }

        @Override
        public boolean isRegistered(BlockDefinition definition) {
            return definition != null;
        }
    }

    private record UnknownItemDefinition(String identifier, int runtimeId) implements ItemDefinition {
        private static UnknownItemDefinition fromRuntimeId(int runtimeId) {
            return new UnknownItemDefinition("minecraft:unknown_" + runtimeId, runtimeId);
        }

        @Override
        public String getIdentifier() {
            return identifier;
        }

        @Override
        public int getRuntimeId() {
            return runtimeId;
        }

        @Override
        public boolean isComponentBased() {
            return false;
        }
    }

    private static final class UnknownItemDefinitionRegistry implements DefinitionRegistry<ItemDefinition> {
        @Override
        public ItemDefinition getDefinition(int runtimeId) {
            if (runtimeId == 0) {
                return ItemDefinition.AIR;
            }
            return UnknownItemDefinition.fromRuntimeId(runtimeId);
        }

        @Override
        public boolean isRegistered(ItemDefinition definition) {
            return definition != null;
        }
    }

    private record CameraPresetDefinition(String identifier, int runtimeId) implements NamedDefinition {
        private static CameraPresetDefinition from(CameraPreset preset, int runtimeId) {
            return new CameraPresetDefinition(preset.getIdentifier(), runtimeId);
        }

        @Override
        public String getIdentifier() {
            return identifier;
        }

        @Override
        public int getRuntimeId() {
            return runtimeId;
        }
    }
}
