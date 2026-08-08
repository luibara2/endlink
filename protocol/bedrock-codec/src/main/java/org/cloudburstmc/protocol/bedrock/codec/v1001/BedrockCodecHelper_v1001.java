package org.cloudburstmc.protocol.bedrock.codec.v1001;

import org.cloudburstmc.protocol.bedrock.codec.EntityDataTypeMap;
import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.v975.BedrockCodecHelper_v975;
import org.cloudburstmc.protocol.bedrock.data.Ability;
import org.cloudburstmc.protocol.bedrock.data.PresenceConfiguration;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.TextProcessingEventOrigin;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestActionType;
import org.cloudburstmc.protocol.common.util.TypeMap;

/**
 * {@code ServerPresenceInfoPacket} keeps its own inline copy of this shape in
 * {@code ServerPresenceInfoSerializer_v1001}; the methods here serve the copy embedded in StartGame's
 * {@code ServerConfigurationJoinInfo}. The two agree by construction — see
 * {@link BedrockCodecHelper_v975#readPresenceConfiguration} for why they have to exist at all.
 */
public class BedrockCodecHelper_v1001 extends BedrockCodecHelper_v975 {

    /** 1.26.30 makes both names optional and appends a required rich presence id. */
    @Override
    public PresenceConfiguration readPresenceConfiguration(ByteBuf buffer) {
        return new PresenceConfiguration(
                this.readOptional(buffer, null, this::readString),
                this.readOptional(buffer, null, this::readString),
                this.readString(buffer));
    }

    @Override
    public void writePresenceConfiguration(ByteBuf buffer, PresenceConfiguration configuration) {
        this.writeOptionalNull(buffer, configuration.getExperienceName(), this::writeString);
        this.writeOptionalNull(buffer, configuration.getWorldName(), this::writeString);
        this.writeString(buffer, configuration.getRichPresenceId() == null ? "" : configuration.getRichPresenceId());
    }

    public BedrockCodecHelper_v1001(EntityDataTypeMap entityData, TypeMap<Class<?>> gameRulesTypes, TypeMap<ItemStackRequestActionType> stackRequestActionTypes,
                                    TypeMap<ContainerSlotType> containerSlotTypes, TypeMap<Ability> abilities, TypeMap<TextProcessingEventOrigin> textProcessingEventOrigins) {
        super(entityData, gameRulesTypes, stackRequestActionTypes, containerSlotTypes, abilities, textProcessingEventOrigins);
    }
}
