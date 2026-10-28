package org.cloudburstmc.protocol.bedrock.codec.v2192.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v1001.serializer.ClientboundAttributeLayerSyncSerializer_v1001;
import org.cloudburstmc.protocol.bedrock.data.attributelayer.*;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraEase;
import org.cloudburstmc.protocol.common.util.VarInts;

public class ClientboundAttributeLayerSyncSerializer_v2192 extends ClientboundAttributeLayerSyncSerializer_v1001 {

    public static final ClientboundAttributeLayerSyncSerializer_v2192 INSTANCE = new ClientboundAttributeLayerSyncSerializer_v2192();

    /**
     * The absent-alignment fallback is this tree's, not upstream's.
     *
     * <p>An {@link EnvironmentAttributeData} decoded by any serializer before 1.26.50 carries a null
     * {@code noiseAlignment}, because those versions have no such field. Dereferencing it to encode
     * for a 1.26.50 client throws inside the serializer, which loses the packet rather than
     * reporting anything. There is no absent form on the wire and the type has exactly one constant,
     * so the neutral value below is the only thing "unset" can be written as.
     */
    private static final NoiseAlignment NO_ALIGNMENT =
            new NoiseAlignment(NoiseAlignment.Type.MIN_LOCAL_TRANSITION_END, 0);

    @Override
    protected void writeEnvironmentAttribute(ByteBuf buf, BedrockCodecHelper helper, EnvironmentAttributeData e) {
        super.writeEnvironmentAttribute(buf, helper, e);

        NoiseAlignment alignment = e.getNoiseAlignment() == null ? NO_ALIGNMENT : e.getNoiseAlignment();
        buf.writeByte(alignment.getType().ordinal());
        VarInts.writeUnsignedInt(buf, alignment.getValue());
    }

    @Override
    protected EnvironmentAttributeData readEnvironmentAttribute(ByteBuf buf, BedrockCodecHelper helper) {
        String name = helper.readStringMaxLen(buf, 128);

        AttributeData from = helper.readOptional(buf, null, b -> readAttributeData(b, helper));
        AttributeData attribute = readAttributeData(buf, helper);
        AttributeData to = helper.readOptional(buf, null, b -> readAttributeData(b, helper));

        int currentTicks = (int) buf.readUnsignedIntLE();
        int totalTicks = (int) buf.readUnsignedIntLE();

        CameraEase easing = CameraEase.fromName(helper.readString(buf));

        int localTransitionTicks = (int) buf.readUnsignedIntLE();
        boolean noiseTransition = buf.readBoolean();

        NoiseAlignment na = new NoiseAlignment(NoiseAlignment.Type.values()[buf.readUnsignedByte()], VarInts.readUnsignedInt(buf));

        return new EnvironmentAttributeData(name, from, attribute, to, currentTicks, totalTicks, easing, null, localTransitionTicks, noiseTransition, null, na);
    }
}
