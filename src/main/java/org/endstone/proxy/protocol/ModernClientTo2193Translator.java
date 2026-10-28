package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.data.definitions.DimensionDefinition;
import org.cloudburstmc.protocol.bedrock.data.sound.SoundData;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundUpdateSoundDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.DimensionDataPacket;
import org.endstone.proxy.protocol.block.BlockStateTranslation;
import org.endstone.proxy.protocol.block.BlockStateUpgrade;

import java.util.List;
import java.util.stream.Stream;

/**
 * Adjacent-version translator for the 1.26.60 (protocol 2225) &rarr; 1.26.50 (protocol 2193) step.
 *
 * <p>The day 1.26.60 lands, clients update themselves and Endstone does not, so 2225 on the player
 * leg against 2193, 2169 or 2168 on the backend leg is the deployment to expect. Longer gaps chain
 * through this edge and the ones below it.</p>
 *
 * <p><b>What the codecs cover.</b> {@code Bedrock_v2225} is ported from CloudburstMC/Protocol's own
 * and checked file by file against Mojang's dump of the stable server, EndstoneMC/protocol-docs
 * {@code r26_u6}: seven packets reshaped and eight added. The new fields an older backend cannot
 * supply are either optional on the wire, carry a default in the packet class, or are written as a
 * documented neutral value by this tree's serializers, so a packet decoded from a 2193 backend
 * re-encodes for a 2225 client rather than being lost inside a writer.</p>
 *
 * <p><b>What this class has to do itself.</b>
 * <ul>
 *   <li><b>Blocks.</b> 1.26.60 gave six vanilla blocks new properties: chorus plant and fire their
 *       connection faces, both repeaters {@code locked}, redstone wire its four sides, scaffolding
 *       {@code bottom}. A block's network id is a hash of its full state, so each of those became a
 *       number a 1.26.60 client has never heard of and would draw as air. The table is a diff of
 *       Mojang's own block metadata, and every added property takes its neutral value on the way
 *       up: wire draws as a dot rather than a line, fire burns on the floor, a chorus stem is its
 *       core. Visible and solid, which is what the client needed, but not the shapes it would work
 *       out against a 1.26.60 server.</li>
 *   <li><b>Sound updates.</b> 1.26.50 sent each kind of update as its own optional field, so one
 *       packet could carry several; 1.26.60 carries exactly one event. The first present one wins,
 *       in the order below. A packet with nothing in it has nothing to say and is dropped.</li>
 *   <li><b>Dimension clouds.</b> New at 1.26.60 and not optional on the wire. Without a value an
 *       older backend's definition would turn its clouds off; Mojang's dimension schema says an
 *       omitted clouds component means height 128, so that is what is written.</li>
 * </ul>
 *
 * <p><b>The packets 1.26.60 added</b> need no rule here clientbound, because only a 2225 backend
 * could send them and this edge runs the other way. Serverbound, the ones a client sends are dropped
 * by {@code ClientRelayPacketHandler.shouldDropCrossProtocolServerbound}, where every packet that
 * exists on one leg and not the other is dropped and logged.</p>
 */
public final class ModernClientTo2193Translator implements PacketTranslator {
    public static final ModernClientTo2193Translator INSTANCE = new ModernClientTo2193Translator();

    static final int DEFAULT_CLOUD_HEIGHT = 128;

    private static final BlockStateTranslation BLOCKS =
            new BlockStateTranslation(BlockStateUpgrade.load("/blockstate/2193-to-2225.json"));

    private ModernClientTo2193Translator() {
    }

    /** The block id table this edge applies, exposed for diagnostics and tests. */
    public static BlockStateTranslation blocks() {
        return BLOCKS;
    }

    @Override
    public BedrockPacket translateServerbound(BedrockPacket packet, TranslationContext context) {
        return BLOCKS.rewriteServerbound(packet, BLOCKS.toOlder());
    }

    @Override
    public BedrockPacket translateClientbound(BedrockPacket packet, TranslationContext context) {
        if (packet instanceof ClientboundUpdateSoundDataPacket sound) {
            return singleSoundEvent(sound);
        }
        if (packet instanceof DimensionDataPacket dimensions) {
            dimensions.getDefinitions().replaceAll(ModernClientTo2193Translator::withClouds);
            return dimensions;
        }
        return BLOCKS.rewriteClientbound(packet, BLOCKS.toNewer());
    }

    @Override
    public AvailableCommandsPacket translateCommandTree(AvailableCommandsPacket packet, TranslationContext context) {
        return packet;
    }

    static BedrockPacket singleSoundEvent(ClientboundUpdateSoundDataPacket packet) {
        if (packet.getEvent() != null) {
            return packet;
        }
        List<SoundData> present = Stream.<SoundData>of(
                        packet.getStop(),
                        packet.getPause(),
                        packet.getResume(),
                        packet.getSeekTo(),
                        packet.getFade(),
                        packet.getVolume(),
                        packet.getPitch())
                .filter(data -> data != null)
                .toList();
        if (present.isEmpty()) {
            return null;
        }
        packet.setEvent(present.get(0));
        return packet;
    }

    static DimensionDefinition withClouds(DimensionDefinition definition) {
        if (definition.getCloudHeight() != 0 || definition.isRenderClouds()) {
            return definition;
        }
        return new DimensionDefinition(
                definition.getId(),
                definition.getMaximumHeight(),
                definition.getMinimumHeight(),
                definition.getGeneratorType(),
                definition.getDimensionType(),
                definition.getPackId(),
                definition.getDefaultBiome(),
                DEFAULT_CLOUD_HEIGHT,
                true);
    }
}
