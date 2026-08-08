package org.cloudburstmc.protocol.bedrock.codec.v2168.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v898.serializer.CommandOutputSerializer_v898;
import org.cloudburstmc.protocol.bedrock.packet.CommandOutputPacket;

/**
 * Appends the optional {@code DataSet} string that closes a command output.
 *
 * <p><b>This is a local addition — upstream CloudburstMC has no v2168 CommandOutput serializer.</b>
 * It is kept because the byte is demonstrably on the wire: a 1.26.40 client's command output
 * re-encoded to 75 bytes against 76 captured, and because the packet decodes without throwing, the
 * truncated copy is what reached the client — which is what made {@code /gamemode c} disconnect.
 * gophertunnel writes the same trailing optional string
 * ({@code minecraft/protocol/packet/command_output.go}), so two independent implementations agree
 * the field exists and CloudburstMC is the one missing it.</p>
 *
 * <p>Scoped to 1.26.40 deliberately. gophertunnel carries it on master (protocol 1001), so this tree
 * is almost certainly a byte short on 1001 and earlier too — but those run in production against
 * real backends and have never shown the symptom, so widening it is a separate, deliberate change
 * and not one to make while chasing a 1.26.40 regression.</p>
 */
public class CommandOutputSerializer_v2168 extends CommandOutputSerializer_v898 {

    public static final CommandOutputSerializer_v2168 INSTANCE = new CommandOutputSerializer_v2168();

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, CommandOutputPacket packet) {
        super.serialize(buffer, helper, packet);
        // Always absent: nothing in this tree models a data set, and a command output that carries
        // one is a /execute-style query the proxy has no reason to synthesise.
        buffer.writeBoolean(false);
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, CommandOutputPacket packet) {
        super.deserialize(buffer, helper, packet);
        if (buffer.isReadable() && buffer.readBoolean()) {
            helper.readString(buffer);
        }
    }
}
