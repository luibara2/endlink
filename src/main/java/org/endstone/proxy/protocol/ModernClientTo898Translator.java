package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.SoundEvent;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelSoundEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;

import java.util.EnumMap;
import java.util.Set;

/**
 * Translates modern Bedrock clients to the proxy's canonical 1.21.130 backend protocol.
 *
 * <p>The Cloudburst packet model is shared across protocol versions, so most packets can pass through after the
 * session codecs are swapped. The unsafe cases are packets or metadata entries that were added after protocol 898:
 * if they reach the 898 encoder, the serializer has no type mapping and the session disconnects. This translator is
 * deliberately conservative and only drops/sanitizes fields that cannot be represented by the canonical backend.</p>
 */
public final class ModernClientTo898Translator implements PacketTranslator {
    public static final ModernClientTo898Translator INSTANCE = new ModernClientTo898Translator();

    private static final Set<String> MODERN_ONLY_SERVERBOUND_PACKETS = Set.of(
            "PartyChangedPacket",
            "ResourcePacksReadyForValidationPacket",
            "ServerboundDataDrivenScreenClosedPacket",
            "ServerboundDiagnosticsPacket"
    );

    private static final Set<String> MODERN_ONLY_CLIENTBOUND_PACKETS = Set.of(
            "CameraAimAssistActorPriorityPacket",
            "CameraSplinePacket",
            "ClientboundAttributeLayerSyncPacket",
            "ClientboundDataDrivenUICloseScreenPacket",
            "ClientboundDataDrivenUIReloadPacket",
            "ClientboundDataDrivenUIShowScreenPacket",
            "ClientboundTextureShiftPacket",
            "DebugDrawerPacket",
            "LocatorBarPacket",
            "ServerPresenceInfoPacket",
            "ServerStoreInfoPacket",
            "SyncWorldClocksPacket",
            "VoxelShapesPacket"
    );

    private ModernClientTo898Translator() {
    }

    @Override
    public BedrockPacket translateServerbound(BedrockPacket packet, TranslationContext context) {
        if (isModernOnlyServerbound(packet)) {
            return null;
        }
        sanitizeEntityMetadata(packet, false);
        return packet;
    }

    @Override
    public BedrockPacket translateClientbound(BedrockPacket packet, TranslationContext context) {
        if (isModernOnlyClientbound(packet)) {
            return null;
        }
        sanitizeEntityMetadata(packet, true);
        return packet;
    }

    @Override
    public AvailableCommandsPacket translateCommandTree(AvailableCommandsPacket packet, TranslationContext context) {
        return packet;
    }

    public boolean isModernOnlyServerbound(BedrockPacket packet) {
        return MODERN_ONLY_SERVERBOUND_PACKETS.contains(packet.getClass().getSimpleName());
    }

    public boolean isModernOnlyClientbound(BedrockPacket packet) {
        return MODERN_ONLY_CLIENTBOUND_PACKETS.contains(packet.getClass().getSimpleName());
    }

    private static void sanitizeEntityMetadata(BedrockPacket packet, boolean clientbound) {
        if (packet instanceof SetEntityDataPacket entityData) {
            sanitizeEntityMetadata(entityData.getMetadata(), clientbound);
        } else if (packet instanceof AddEntityPacket addEntity) {
            sanitizeEntityMetadata(addEntity.getMetadata(), clientbound);
        } else if (packet instanceof AddPlayerPacket addPlayer) {
            sanitizeEntityMetadata(addPlayer.getMetadata(), clientbound);
        }
    }

    private static void sanitizeEntityMetadata(EntityDataMap metadata, boolean clientbound) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }

        metadata.remove(EntityDataTypes.AIM_ASSIST_PRIORITY_PRESET_ID);
        metadata.remove(EntityDataTypes.AIM_ASSIST_PRIORITY_CATEGORY_ID);
        metadata.remove(EntityDataTypes.AIM_ASSIST_PRIORITY_ACTOR_ID);
        metadata.remove(EntityDataTypes.RESERVED_139);
        metadata.remove(EntityDataTypes.NAMEPLATE_RENDER_DISTANCE_MAX);

        Integer heartbeatSoundEvent = metadata.get(EntityDataTypes.HEARTBEAT_SOUND_EVENT);
        if (clientbound && heartbeatSoundEvent != null) {
            metadata.putType(EntityDataTypes.HEARTBEAT_SOUND_EVENT, remap898To975SoundId(heartbeatSoundEvent));
        }

        removeModernOnlyFlag(metadata.getFlags(), EntityFlag.USES_LEGACY_FRICTION);
        removeModernOnlyFlag(metadata.getFlags(), EntityFlag.USES_UNIFORM_AIR_DRAG);
        removeModernOnlyFlag(metadata.getFlags(), EntityFlag.NAMEPLATE_DEPTH_TESTED);
    }

    private static void removeModernOnlyFlag(EnumMap<EntityFlag, Boolean> flags, EntityFlag flag) {
        if (flags != null) {
            flags.remove(flag);
        }
    }

    /**
     * Sound ids only need remapping where they travel as a bare int. {@code LevelSoundEventPacket} is not
     * one of those: it carries a {@link SoundEvent} that each codec maps through its own id table, so the
     * backend codec decodes 898 ids and the client codec re-encodes the newer ids on its own. Entity
     * metadata is different &mdash; {@code HEARTBEAT_SOUND_EVENT} is a plain int the codec never touches,
     * so the id shift has to be applied by hand.
     */
    private static int remap898To975SoundId(int soundId) {
        int mapped = soundId;
        if (mapped >= 568) {
            mapped += 19;
        }
        if (mapped >= 587) {
            mapped += 2;
        }
        if (mapped >= 589) {
            mapped += 2;
        }
        return mapped;
    }
}
