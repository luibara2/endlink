package org.cloudburstmc.protocol.bedrock.codec.v2168.serializer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.PlayerListGuard;
import org.cloudburstmc.protocol.bedrock.codec.v557.serializer.AddPlayerSerializer_v557;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;

/**
 * The other half of the BDS 1.26.40 truncation that {@link PlayerListSerializer_v2168} repairs.
 *
 * <p>For the same malformed identity, BDS also stops writing {@code AddPlayer} one field early: the
 * trailing four-byte build platform is missing. Repairing only the player list is not enough — the
 * {@code endstone-playerlist-guard} plugin shipped three versions that still disconnected clients
 * before that was found, which is why this exists alongside the list repair rather than after it.</p>
 *
 * <p>The completion is deliberately blind to which UUID it is: it retries only when the inherited
 * reader ran off the end, and only by supplying the one field that is missing. A packet that reads
 * cleanly is untouched, so this costs nothing on the overwhelming majority of spawns.</p>
 */
public class AddPlayerSerializer_v2168 extends AddPlayerSerializer_v557 {

    public static final AddPlayerSerializer_v2168 INSTANCE = new AddPlayerSerializer_v2168();

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, AddPlayerPacket packet) {
        int start = buffer.readerIndex();
        try {
            super.deserialize(buffer, helper, packet);
            return;
        } catch (IndexOutOfBoundsException truncated) {
            buffer.readerIndex(start);
        }

        byte[] body = new byte[buffer.readableBytes()];
        buffer.getBytes(start, body);
        byte[] completed = PlayerListGuard.completeAddPlayerBuildPlatform(body);
        if (completed.length == body.length) {
            // Nothing was missing, so the failure was something else entirely. Let the original
            // exception path repeat rather than pretend this is the known truncation.
            super.deserialize(buffer, helper, packet);
            return;
        }

        ByteBuf repaired = Unpooled.wrappedBuffer(completed);
        try {
            super.deserialize(repaired, helper, packet);
            if (repaired.isReadable()) {
                throw new IndexOutOfBoundsException(
                        "AddPlayer still had " + repaired.readableBytes() + " bytes after repair");
            }
            // Silent: this pairs with a PlayerList repair that is itself silent, and it recurs for
            // every recipient of the same spawn. Nothing was lost, so there is nothing to report.
            buffer.skipBytes(body.length);
        } finally {
            repaired.release();
        }
    }
}
