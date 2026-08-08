package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

import java.util.Set;

/**
 * Adjacent-version translator for the 1.26.30 (protocol 1001) &harr; 1.26.20 (protocol 975) step.
 *
 * <p>Everything that changed shape on the wire in 1.26.30 is handled by the v1001 codec itself
 * ({@code StartGamePacket}, {@code BiomeDefinitionListPacket}, {@code InventoryContentPacket},
 * {@code InventoryTransactionPacket}, {@code MobArmorEquipmentPacket}, {@code SubChunkRequestPacket},
 * {@code ClientCacheBlobStatusPacket}, {@code BossEventPacket}, {@code LevelSoundEventPacket} and the
 * attribute-layer/debug-shape payloads). Since the proxy decodes with the backend codec and re-encodes
 * with the client codec, those conversions happen for free and this translator only has to deal with what
 * the shared packet model cannot express: packets that exist on one side and not the other.</p>
 *
 * <p>Fields that 1.26.30 added and an older backend never sets simply stay at their defaults, which the
 * v1001 serializers write as absent/empty. The two places where that default would be an illegal
 * {@code null} string are guarded inside the v1001 serializers themselves.</p>
 */
public final class ModernClientTo975Translator implements PacketTranslator {
    public static final ModernClientTo975Translator INSTANCE = new ModernClientTo975Translator();

    /**
     * Registered by the v1001 codec only, so a 1.26.20-or-older backend has no id to encode them with.
     */
    private static final Set<String> V1001_ONLY_SERVERBOUND_PACKETS = Set.of(
            "PartyDestinationCookieResponsePacket"
    );

    private ModernClientTo975Translator() {
    }

    @Override
    public BedrockPacket translateServerbound(BedrockPacket packet, TranslationContext context) {
        if (V1001_ONLY_SERVERBOUND_PACKETS.contains(packet.getClass().getSimpleName())) {
            return null;
        }
        return packet;
    }

    @Override
    public BedrockPacket translateClientbound(BedrockPacket packet, TranslationContext context) {
        // 975 -> 1001: no clientbound packet was removed, and every reshaped one is handled by the codec.
        return packet;
    }

    @Override
    public AvailableCommandsPacket translateCommandTree(AvailableCommandsPacket packet, TranslationContext context) {
        return packet;
    }
}
