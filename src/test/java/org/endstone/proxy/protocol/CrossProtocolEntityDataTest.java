package org.endstone.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataType;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Entity metadata across the 1.26.40 &harr; 1.26.30 hop, one data type at a time.
 *
 * <p>A player reported spawning fine, breaking blocks, then being disconnected the moment a skeleton
 * came into view. A mob entering view is the first time most of these types are ever sent — a player
 * spawning into an empty area exercises almost none of them — so a single type that cannot cross the
 * hop looks exactly like "it worked for a few seconds and then dropped me".</p>
 *
 * <p>Each type is hopped on its own rather than in one big map, because the failure has to name the
 * type. A combined map fails on the first bad entry and hides the rest.</p>
 */
class CrossProtocolEntityDataTest {

    @Test
    void everyEntityDataTypeA1_26_30BackendCanSendSurvivesTheHop() {
        List<String> broken = new ArrayList<>();

        for (EntityDataType<?> type : allEntityDataTypes()) {
            Object value = sampleValue(type);
            if (value == null) {
                continue;
            }
            String failure = hopFailure(type, value);
            if (failure != null) {
                broken.add(type + " (" + value.getClass().getSimpleName() + ") — " + failure);
            }
        }

        if (!broken.isEmpty()) {
            throw new AssertionError("""
                    These entity data types cannot be relayed from a 1.26.30 backend to a 1.26.40 \
                    client. Entity metadata is sent for every mob that comes into view, so each of \
                    these drops the session seconds after a player spawns:
                      """ + String.join("\n  ", broken));
        }
    }

    /**
     * Entity flags travel as a packed long whose bit positions come from the codec's flag map, and
     * 1.26.40 inserted one. {@code FlagTransformer.serialize} resolves every flag through
     * {@code TypeMap.getId}, which throws rather than skipping when a flag is unknown to the target.
     */
    @Test
    void entityFlagsSurviveTheHop() {
        EnumMap<EntityFlag, Boolean> flags = new EnumMap<>(EntityFlag.class);
        flags.put(EntityFlag.ON_FIRE, true);
        flags.put(EntityFlag.SNEAKING, false);
        flags.put(EntityFlag.CAN_SHOW_NAME, true);

        String failure = hopFailure(EntityDataTypes.FLAGS, flags);
        if (failure != null) {
            throw new AssertionError("entity flags cannot cross the hop: " + failure);
        }
    }

    /** Encodes for 1.26.30, decodes it there, then re-encodes for 1.26.40 — the relay's real path. */
    private static String hopFailure(EntityDataType<?> type, Object value) {
        SetEntityDataPacket packet = new SetEntityDataPacket();
        packet.setRuntimeEntityId(1L);
        putUnchecked(packet, type, value);

        int id = Bedrock_v1001.CODEC.getPacketDefinition(SetEntityDataPacket.class).getId();

        SetEntityDataPacket decoded;
        try {
            decoded = (SetEntityDataPacket) roundTrip(Bedrock_v1001.CODEC, packet, id);
        } catch (Throwable ignored) {
            // 1.26.30 cannot carry this type at all, so it can never reach the hop. Not a finding —
            // and reporting it would have blamed the hop for UNKNOWN_HORSE_INT_25, which is a
            // 1.26.40-only type that a 1.26.30 backend has no way to send.
            return null;
        }

        try {
            encode(Bedrock_v2168.CODEC, decoded);
            return null;
        } catch (Throwable failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void putUnchecked(SetEntityDataPacket packet, EntityDataType<?> type, Object value) {
        packet.getMetadata().put((EntityDataType) type, value);
    }

    private static Object roundTrip(BedrockCodec codec, SetEntityDataPacket packet, int id) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(), buffer, packet);
            return codec.tryDecode(codec.createHelper(), buffer, id);
        } finally {
            buffer.release();
        }
    }

    private static void encode(BedrockCodec codec, SetEntityDataPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(), buffer, packet);
        } finally {
            buffer.release();
        }
    }

    private static List<EntityDataType<?>> allEntityDataTypes() {
        List<EntityDataType<?>> types = new ArrayList<>();
        for (Field field : EntityDataTypes.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != EntityDataType.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                types.add((EntityDataType<?>) field.get(null));
            } catch (IllegalAccessException ignored) {
                // Not readable, not testable.
            }
        }
        return types;
    }

    /** A value of whatever class the type declares; flags are covered by their own test. */
    private static Object sampleValue(EntityDataType<?> type) {
        Class<?> valueClass = declaredValueClass(type);
        if (valueClass == null || EnumMap.class.isAssignableFrom(valueClass)) {
            return null;
        }
        if (valueClass == Byte.class) return (byte) 1;
        if (valueClass == Short.class) return (short) 1;
        if (valueClass == Integer.class) return 1;
        if (valueClass == Long.class) return 1L;
        if (valueClass == Float.class) return 1.0f;
        if (valueClass == String.class) return "x";
        if (valueClass == NbtMap.class) return NbtMap.EMPTY;
        if (valueClass == Vector3f.class) return Vector3f.from(1f, 2f, 3f);
        if (valueClass == Vector3i.class) return Vector3i.from(1, 2, 3);
        // Anything else (block definitions and the like) needs a registry this test does not have.
        return null;
    }

    private static Class<?> declaredValueClass(EntityDataType<?> type) {
        try {
            Field field = EntityDataType.class.getDeclaredField("type");
            field.setAccessible(true);
            return (Class<?>) field.get(type);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
