package org.cloudburstmc.protocol.bedrock.codec.v924;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.EntityDataTypeMap;
import org.cloudburstmc.protocol.bedrock.codec.v898.BedrockCodecHelper_v898;
import org.cloudburstmc.protocol.bedrock.data.Ability;
import org.cloudburstmc.protocol.bedrock.data.GatheringsConfigurationJoinInfo;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.TextProcessingEventOrigin;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestActionType;
import org.cloudburstmc.protocol.common.util.TypeMap;

public class BedrockCodecHelper_v924 extends BedrockCodecHelper_v898 {

    public BedrockCodecHelper_v924(EntityDataTypeMap entityData, TypeMap<Class<?>> gameRulesTypes, TypeMap<ItemStackRequestActionType> stackRequestActionTypes,
                                   TypeMap<ContainerSlotType> containerSlotTypes, TypeMap<Ability> abilities, TypeMap<TextProcessingEventOrigin> textProcessingEventOrigins) {
        super(entityData, gameRulesTypes, stackRequestActionTypes, containerSlotTypes, abilities, textProcessingEventOrigins);
    }

    @Override
    public void writeGatheringsConfiguration(ByteBuf buf, BedrockCodecHelper h, GatheringsConfigurationJoinInfo info) {
        h.writeUuid(buf, info.getExperienceId());
        h.writeString(buf, info.getExperienceName());
        h.writeUuid(buf, info.getWorldId());
        h.writeString(buf, info.getWorldName());
        h.writeString(buf, info.getCreatorId());
        h.writeUuid(buf, info.getTargetId());
        h.writeString(buf, info.getScenarioId());
        h.writeString(buf, info.getServerId());
    }

    @Override
    public GatheringsConfigurationJoinInfo readGatheringsConfiguration(ByteBuf buf, BedrockCodecHelper h) {
        return new GatheringsConfigurationJoinInfo(
                h.readUuid(buf),
                h.readString(buf),
                h.readUuid(buf),
                h.readString(buf),
                h.readString(buf),
                h.readUuid(buf),
                h.readString(buf),
                h.readString(buf)
        );
    }
}
