package org.cloudburstmc.protocol.bedrock.codec.v944.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v924.serializer.StartGameSerializer_v924;
import org.cloudburstmc.protocol.bedrock.data.ClientStoreEntrypointConfiguration;
import org.cloudburstmc.protocol.bedrock.data.GatheringsConfigurationJoinInfo;
import org.cloudburstmc.protocol.bedrock.data.PresenceConfiguration;
import org.cloudburstmc.protocol.bedrock.data.ServerConfigurationJoinInfo;

public class StartGameSerializer_v944 extends StartGameSerializer_v924 {

    public static final StartGameSerializer_v944 INSTANCE = new StartGameSerializer_v944();

    @Override
    protected void writeServerJoinInfo(ByteBuf buffer, BedrockCodecHelper helper, ServerConfigurationJoinInfo info) {
        helper.writeOptionalNull(buffer, info.getGatheringsConfigurationJoinInfo(), helper::writeGatheringsConfiguration);
        helper.writeOptionalNull(buffer, info.getClientStoreEntrypointConfiguration(), this::writeClientStoreEntrypointConfiguration);
        helper.writeOptionalNull(buffer, info.getPresenceConfiguration(), helper::writePresenceConfiguration);
    }

    private void writeClientStoreEntrypointConfiguration(ByteBuf buf, BedrockCodecHelper h, ClientStoreEntrypointConfiguration store) {
        h.writeString(buf, store.getStoreId());
        h.writeString(buf, store.getStoreName());
    }

    @Override
    protected ServerConfigurationJoinInfo readServerJoinInfo(ByteBuf buffer, BedrockCodecHelper helper) {
        GatheringsConfigurationJoinInfo gatheringsConfigurationJoinInfo = helper.readOptional(buffer, null, helper::readGatheringsConfiguration);
        ClientStoreEntrypointConfiguration clientStoreEntrypointConfiguration = helper.readOptional(buffer, null, this::readClientStoreEntrypointConfiguration);
        PresenceConfiguration presenceConfiguration = helper.readOptional(buffer, null, helper::readPresenceConfiguration);

        return new ServerConfigurationJoinInfo(gatheringsConfigurationJoinInfo, clientStoreEntrypointConfiguration, presenceConfiguration);
    }

    private ClientStoreEntrypointConfiguration readClientStoreEntrypointConfiguration(ByteBuf buf, BedrockCodecHelper h) {
        return new ClientStoreEntrypointConfiguration(
                h.readString(buf),
                h.readString(buf)
        );
    }
}
