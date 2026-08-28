package org.cloudburstmc.protocol.bedrock.codec.v2169;

import org.cloudburstmc.protocol.bedrock.codec.EntityDataTypeMap;
import org.cloudburstmc.protocol.bedrock.codec.v2168.BedrockCodecHelper_v2168;
import org.cloudburstmc.protocol.bedrock.data.Ability;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.TextProcessingEventOrigin;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestActionType;
import org.cloudburstmc.protocol.common.util.TypeMap;

/**
 * 1.26.45 writes a {@code RemoveScore} entry the way 1.26.40 did, without the constant {@code true}
 * that 1.26.44 puts ahead of the objective name's own presence flag.
 *
 * <p>That single field is the <em>whole</em> wire difference between protocol 2168 and 2169. Mojang's
 * own schema dump for 1.26.45.1 changes three files against 1.26.44.3: this type, the protocol number
 * in {@code RequestNetworkSettingsPacket}, and the README. Nothing else about the format moved, which
 * is why this codec inherits every serializer from
 * {@link org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168} and changes only the flag below.
 *
 * <p>The flag is <strong>fixed</strong> here rather than defaulted. On 2168 it is per-connection state
 * derived from the peer's Minecraft version, because five releases share that protocol number and
 * disagree about this byte. 2169 has no such ambiguity — the protocol number identifies the shape on
 * its own — so {@link org.endstone.proxy.protocol.BedrockRelease#applyTo} must not be able to talk
 * this helper into the 1.26.44 shape by reading a version string it does not understand. Its setter
 * is deliberately inert: {@code applyTo} tests for the 2168 helper, this class is one, and a peer
 * reporting an unparseable version defaults to {@code true} there. Accepting that write would corrupt
 * every scoreboard removal sent to a 2169 peer.
 *
 * @see org.cloudburstmc.protocol.bedrock.codec.v2168.serializer.SetScoreSerializer_v2168
 */
public class BedrockCodecHelper_v2169 extends BedrockCodecHelper_v2168 {

    public BedrockCodecHelper_v2169(EntityDataTypeMap entityData, TypeMap<Class<?>> gameRulesTypes,
                                    TypeMap<ItemStackRequestActionType> stackRequestActionTypes,
                                    TypeMap<ContainerSlotType> containerSlotTypes, TypeMap<Ability> abilities,
                                    TypeMap<TextProcessingEventOrigin> textProcessingEventOrigins) {
        super(entityData, gameRulesTypes, stackRequestActionTypes, containerSlotTypes, abilities, textProcessingEventOrigins);
    }

    /** Always {@code false}: 1.26.45 dropped the constant. */
    @Override
    public boolean isRemoveScoreKeyedConstant() {
        return false;
    }

    /** Inert on purpose. See the class javadoc. */
    @Override
    public void setRemoveScoreKeyedConstant(boolean removeScoreKeyedConstant) {
        // A 2169 peer's shape is settled by its protocol number; nothing may override it.
    }
}
