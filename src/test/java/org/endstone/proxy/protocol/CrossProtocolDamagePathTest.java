package org.endstone.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The packets a player sees when they take damage, across the 1.26.40 &harr; 1.26.30 hop.
 *
 * <p>Reported symptom: spawning worked, breaking blocks worked, and the session dropped on the first
 * damage — once when a skeleton appeared, once on fall damage. Those two have nothing in common
 * except taking damage, which narrows it to the handful of packets below. Health arrives as an
 * attribute update, the flinch as an entity event.</p>
 */
class CrossProtocolDamagePathTest {

    @Test
    void theDamagePacketsRelayFromA1_26_30BackendToA1_26_40Client() {
        List<String> broken = new ArrayList<>();

        check(broken, "UpdateAttributes (health)", healthUpdate());
        check(broken, "EntityEvent (hurt)", hurtEvent());

        if (!broken.isEmpty()) {
            throw new AssertionError("""
                    The damage path cannot cross the hop. Every one of these is sent the instant a \
                    player is hurt, so the session drops on first damage:
                      """ + String.join("\n  ", broken));
        }
    }

    private static UpdateAttributesPacket healthUpdate() {
        UpdateAttributesPacket packet = new UpdateAttributesPacket();
        packet.setRuntimeEntityId(1L);
        packet.setTick(42L);
        // What a backend actually sends after a hit: current below max, with the modifier list the
        // 1.26 attribute format carries.
        packet.getAttributes().add(new AttributeData("minecraft:health", 0f, 20f, 14f, 20f));
        return packet;
    }

    private static EntityEventPacket hurtEvent() {
        EntityEventPacket packet = new EntityEventPacket();
        packet.setRuntimeEntityId(1L);
        packet.setType(EntityEventType.HURT);
        packet.setData(0);
        return packet;
    }

    private static void check(List<String> broken, String label, BedrockPacket packet) {
        int id = Bedrock_v1001.CODEC.getPacketDefinition(packet.getClass().asSubclass(BedrockPacket.class)).getId();

        BedrockPacket decoded;
        try {
            decoded = roundTrip(Bedrock_v1001.CODEC, packet, id);
        } catch (Throwable failure) {
            broken.add(label + " — cannot even round-trip on 1.26.30: " + rootCause(failure));
            return;
        }

        try {
            ByteBuf buffer = Unpooled.buffer();
            try {
                Bedrock_v2168.CODEC.tryEncode(Bedrock_v2168.CODEC.createHelper(), buffer, decoded);
            } finally {
                buffer.release();
            }
        } catch (Throwable failure) {
            broken.add(label + " — " + rootCause(failure));
        }
    }

    private static BedrockPacket roundTrip(BedrockCodec codec, BedrockPacket packet, int id) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(), buffer, packet);
            return codec.tryDecode(codec.createHelper(), buffer, id);
        } finally {
            buffer.release();
        }
    }

    private static String rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
