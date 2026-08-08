package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.attributelayer.AttributeLayerData;
import org.cloudburstmc.protocol.bedrock.data.attributelayer.AttributeLayerSettings;
import org.cloudburstmc.protocol.bedrock.data.attributelayer.UpdateAttributeLayerSettingsData;
import org.cloudburstmc.protocol.bedrock.data.attributelayer.UpdateAttributeLayersData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.RecipeUnlockingRequirement;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.FurnaceRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.RecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.ShapelessRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.DefaultDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemDescriptorWithCount;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundAttributeLayerSyncPacket;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelSoundEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlaySoundPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateClientOptionsPacket;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Translates a 1.26.20 client to a 1.26.10 backend.
 */
public final class ModernClientTo944Translator implements PacketTranslator {
    public static final ModernClientTo944Translator INSTANCE = new ModernClientTo944Translator();

    private static final int V975_SOUND_INSERTION_ID = 599;
    private static final int V975_SOUND_INSERTION_COUNT = 2;

    private static final Set<String> V975_ONLY_SERVERBOUND_PACKETS = Set.of(
            "ServerboundDiagnosticsPacket"
    );

    private static final Set<String> V975_ONLY_CLIENTBOUND_PACKETS = Set.of(
            "ServerPresenceInfoPacket",
            "ServerStoreInfoPacket"
    );

    private ModernClientTo944Translator() {
    }

    @Override
    public BedrockPacket translateServerbound(BedrockPacket packet, TranslationContext context) {
        if (V975_ONLY_SERVERBOUND_PACKETS.contains(packet.getClass().getSimpleName())) {
            return null;
        }
        if (packet instanceof EntityEventPacket entityEvent) {
            entityEvent.setFireAtPosition(null);
        } else if (packet instanceof LevelSoundEventPacket soundEvent) {
            soundEvent.setFireAtPosition(null);
        } else if (packet instanceof PlaySoundPacket playSound) {
            playSound.setServerSoundHandle(null);
        } else if (packet instanceof UpdateClientOptionsPacket options) {
            options.setFilterProfanityChange(null);
        }
        sanitizeV975OnlyEntityState(packet);
        return packet;
    }

    @Override
    public BedrockPacket translateClientbound(BedrockPacket packet, TranslationContext context) {
        if (V975_ONLY_CLIENTBOUND_PACKETS.contains(packet.getClass().getSimpleName())) {
            return null;
        }
        if (packet instanceof StartGamePacket startGame) {
            startGame.setBlockRegistryChecksum(0);
        } else if (packet instanceof LevelSoundEventPacket soundEvent) {
            soundEvent.setFireAtPosition(null);
        } else if (packet instanceof EntityEventPacket entityEvent) {
            entityEvent.setFireAtPosition(null);
        } else if (packet instanceof PlaySoundPacket playSound) {
            playSound.setServerSoundHandle(null);
        } else if (packet instanceof CraftingDataPacket craftingData) {
            rewriteFurnaceRecipes(craftingData);
        } else if (packet instanceof ClientboundAttributeLayerSyncPacket attributeLayerSync) {
            rewriteAttributeLayerStringWeights(attributeLayerSync);
        }
        sanitizeV975OnlyEntityState(packet);
        return packet;
    }

    @Override
    public AvailableCommandsPacket translateCommandTree(AvailableCommandsPacket packet, TranslationContext context) {
        return packet;
    }

    private static void sanitizeV975OnlyEntityState(BedrockPacket packet) {
        if (packet instanceof SetEntityDataPacket entityData) {
            sanitizeV975OnlyEntityState(entityData.getMetadata());
        } else if (packet instanceof AddEntityPacket addEntity) {
            sanitizeV975OnlyEntityState(addEntity.getMetadata());
        } else if (packet instanceof AddPlayerPacket addPlayer) {
            sanitizeV975OnlyEntityState(addPlayer.getMetadata());
        }
    }

    private static void sanitizeV975OnlyEntityState(EntityDataMap metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        metadata.remove(EntityDataTypes.RESERVED_139);
        metadata.remove(EntityDataTypes.NAMEPLATE_RENDER_DISTANCE_MAX);
        removeFlag(metadata, EntityFlag.USES_LEGACY_FRICTION);
        removeFlag(metadata, EntityFlag.USES_UNIFORM_AIR_DRAG);
        removeFlag(metadata, EntityFlag.NAMEPLATE_DEPTH_TESTED);

        Integer heartbeatSoundEvent = metadata.get(EntityDataTypes.HEARTBEAT_SOUND_EVENT);
        if (heartbeatSoundEvent != null) {
            metadata.putType(EntityDataTypes.HEARTBEAT_SOUND_EVENT, remap944To975SoundId(heartbeatSoundEvent));
        }
    }

    private static void removeFlag(EntityDataMap metadata, EntityFlag flag) {
        if (metadata.getFlags() != null) {
            metadata.getFlags().remove(flag);
        }
    }

    private static int remap944To975SoundId(int soundId) {
        return soundId >= V975_SOUND_INSERTION_ID ? soundId + V975_SOUND_INSERTION_COUNT : soundId;
    }

    private static void rewriteFurnaceRecipes(CraftingDataPacket packet) {
        List<RecipeData> rewritten = new ArrayList<>(packet.getCraftingData().size());
        int nextSyntheticNetId = packet.getCraftingData().stream()
                .filter(ShapelessRecipeData.class::isInstance)
                .map(ShapelessRecipeData.class::cast)
                .mapToInt(ShapelessRecipeData::getNetId)
                .max()
                .orElse(0) + 1;
        for (RecipeData recipe : packet.getCraftingData()) {
            if (recipe instanceof FurnaceRecipeData furnace) {
                rewritten.add(furnaceToShapeless(furnace, nextSyntheticNetId++));
            } else {
                rewritten.add(recipe);
            }
        }
        packet.getCraftingData().clear();
        packet.getCraftingData().addAll(rewritten);
    }

    private static ShapelessRecipeData furnaceToShapeless(FurnaceRecipeData furnace, int netId) {
        int auxValue = furnace.hasData() ? furnace.getInputData() : 0x7fff;
        String tag = furnace.getTag() == null || furnace.getTag().isBlank() ? "furnace" : furnace.getTag();
        String recipeId = "endstone-proxy:" + tag + "_" + furnace.getInputId() + "_" + auxValue
                + "_to_" + furnace.getResult().getDefinition().getRuntimeId();
        return ShapelessRecipeData.shapeless(
                recipeId,
                List.of(new ItemDescriptorWithCount(
                        new DefaultDescriptor(
                                new SimpleItemDefinition("minecraft:legacy_" + furnace.getInputId(), furnace.getInputId(), false),
                                auxValue
                        ),
                        1
                )),
                List.of(furnace.getResult()),
                UUID.nameUUIDFromBytes(recipeId.getBytes(StandardCharsets.UTF_8)),
                tag,
                0,
                netId,
                new RecipeUnlockingRequirement(RecipeUnlockingRequirement.UnlockingContext.ALWAYS_UNLOCKED)
        );
    }

    private static void rewriteAttributeLayerStringWeights(ClientboundAttributeLayerSyncPacket packet) {
        if (packet.getData() instanceof UpdateAttributeLayersData updateLayers) {
            List<AttributeLayerData> layers = new ArrayList<>(updateLayers.getAttributeLayers().size());
            for (AttributeLayerData layer : updateLayers.getAttributeLayers()) {
                layers.add(new AttributeLayerData(
                        layer.getLayerName(),
                        layer.getNoiseName(),
                        layer.getDimension(),
                        rewriteStringWeight(layer.getSettings()),
                        layer.getAttributes()
                ));
            }
            packet.setData(new UpdateAttributeLayersData(layers));
        } else if (packet.getData() instanceof UpdateAttributeLayerSettingsData settings) {
            packet.setData(new UpdateAttributeLayerSettingsData(
                    settings.getLayerName(),
                    settings.getDimension(),
                    rewriteStringWeight(settings.getSettings())
            ));
        }
    }

    private static AttributeLayerSettings rewriteStringWeight(AttributeLayerSettings settings) {
        if (settings == null || !(settings.getWeight() instanceof AttributeLayerSettings.StringWeight)) {
            return settings;
        }
        return new AttributeLayerSettings(
                settings.getPriority(),
                new AttributeLayerSettings.FloatWeight(1.0f),
                settings.isEnabled(),
                settings.isTransitionsPaused()
        );
    }
}
