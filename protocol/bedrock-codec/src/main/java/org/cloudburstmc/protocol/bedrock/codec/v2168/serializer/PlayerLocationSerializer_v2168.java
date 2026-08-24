package org.cloudburstmc.protocol.bedrock.codec.v2168.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.PacketValidationException;
import org.cloudburstmc.protocol.bedrock.codec.v800.serializer.PlayerLocationSerializer_v800;
import org.cloudburstmc.protocol.bedrock.packet.PlayerLocationPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

/**
 * The locator-bar update, which carries its variant twice in two different encodings.
 *
 * <pre>
 * Target Actor ID   varint64
 * &lt;variant&gt;         <b>uvarint32</b>   CoordinatesLocation = 0, HiddenLocation = 1
 * Packet Type       <b>varint32</b>    the same value again, zigzag this time
 * Position          Vec3         CoordinatesLocation only
 * </pre>
 *
 * <p>So HIDE is {@code 01 02} on the wire and COORDINATES is {@code 00 00}. The mixed encoding is
 * the trap: the {@code r26_u4} dump labels both fields {@code varint32}, and taking that at face
 * value gives {@code 02 02}, which the client rejects because the first tag is not zigzag.
 * gophertunnel's {@code player_location.go} is the authority here - it writes
 * {@code IntegerFunc(&amp;Type, Varuint32)} then {@code Varint32(&amp;Type)}.</p>
 *
 * <p><b>The second field is the authoritative one and used to be written as a hardcoded zero</b>,
 * here and in gophertunnel (which fixed it in {@code 1d9d0fc}, "Fix double type encoding"). A
 * decoder assigns the variant from the first tag and then overwrites it from the second, so
 * {@code 01 00} announces HIDE and then says COORDINATES - and the client goes looking for a Vec3
 * that was never written, runs off the end of the packet, and closes the connection with
 * {@code BadPacket} and no message.</p>
 *
 * <p>COORDINATES is 0 in both encodings and the old hardcoded second field was also 0, which is why
 * the common case always worked and only hiding a player from the locator bar ever broke.</p>
 */
public class PlayerLocationSerializer_v2168 extends PlayerLocationSerializer_v800 {

    public static final PlayerLocationSerializer_v2168 INSTANCE = new PlayerLocationSerializer_v2168();

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, PlayerLocationPacket packet) {
        VarInts.writeLong(buffer, packet.getTargetEntityId());

        int type = packet.getType().ordinal();
        // Same value, two encodings - see the class javadoc. Written from one variable because a
        // decoder takes the second as authoritative, so any disagreement is silently the second one.
        VarInts.writeUnsignedInt(buffer, type);
        VarInts.writeInt(buffer, type);

        if (packet.getType() == PlayerLocationPacket.Type.COORDINATES) {
            helper.writeVector3f(buffer, packet.getPosition());
        }
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, PlayerLocationPacket packet) {
        packet.setTargetEntityId(VarInts.readLong(buffer));

        int variant = VarInts.readUnsignedInt(buffer);
        int type = VarInts.readInt(buffer);
        if (variant != type || type < 0 || type >= VALUES.length) {
            // Rejected rather than guessed at. Both tags always carry the same value when BDS writes
            // this packet, so a mismatch means whatever produced it got the double encoding wrong -
            // and the recipient will not survive it. Naming both numbers is the point: 1 and 0 says
            // "the second field was left at the old hardcoded zero", which is the whole bug.
            throw new PacketValidationException(
                    "PlayerLocation announces variant " + variant + " then restates it as " + type
                            + "; a valid packet repeats one of " + VALUES.length
                            + " variants in both fields, so this cannot be relayed");
        }
        packet.setType(VALUES[type]);

        if (packet.getType() == PlayerLocationPacket.Type.COORDINATES) {
            packet.setPosition(helper.readVector3f(buffer));
        }
    }
}
