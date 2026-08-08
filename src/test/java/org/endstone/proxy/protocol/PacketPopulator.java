package org.endstone.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.common.util.OptionalBoolean;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.UUID;

/**
 * Fills a packet's null fields with plausible values so it can actually be encoded.
 *
 * <p>This exists because a sweep over default-constructed packets is close to useless. A default
 * instance has null enums, null strings and null vectors, so the big packets — {@code StartGame},
 * {@code ResourcePackClientResponse}, the whole join sequence — cannot be encoded at all and get
 * skipped. Those are precisely the packets that decide whether a player spawns, so skipping them
 * means the sweep passes loudest exactly where it matters least.</p>
 *
 * <p>Best-effort by design: a field it cannot fill is left null, and the packet either still encodes
 * or gets skipped as before. Every value is chosen to be boring — the point is to reach the
 * serializer, not to be realistic.</p>
 */
final class PacketPopulator {

    private PacketPopulator() {
    }

    static <T> T populate(T target) {
        populate(target, 0);
        return target;
    }

    private static void populate(Object target, int depth) {
        if (target == null || depth > 3) {
            return;
        }
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    if (field.get(target) != null) {
                        continue;
                    }
                    Object value = valueFor(field.getType(), depth);
                    if (value != null) {
                        field.set(target, value);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // A field we cannot fill is not a test failure; the packet just may not encode.
                }
            }
        }
    }

    private static Object valueFor(Class<?> type, int depth) {
        if (type.isEnum()) {
            return enumConstant(type);
        }
        if (type == String.class || type == CharSequence.class) {
            return "x";
        }
        if (ByteBuf.class.isAssignableFrom(type)) {
            // LevelChunk and friends hold their payload as a buffer; null means "toWrite" NPEs.
            return Unpooled.EMPTY_BUFFER;
        }
        if (type == ItemData.class) {
            return ItemData.AIR;
        }
        // Both are interfaces, so without these they fall through to the isInterface() bail below and
        // stay null — and a serializer that reaches one calls getRuntimeId() on it. PlayerAuthInput's
        // ItemUseTransaction was skipped entirely for exactly this reason until its optional started
        // being written whenever the transaction is non-null.
        if (type == BlockDefinition.class) {
            return new SimpleBlockDefinition("minecraft:air", 0, NbtMap.EMPTY);
        }
        if (type == ItemDefinition.class) {
            return ItemDefinition.AIR;
        }
        if (type == UUID.class) {
            return new UUID(1L, 2L);
        }
        if (type == Vector3f.class) {
            return Vector3f.from(1f, 2f, 3f);
        }
        if (type == Vector3i.class) {
            return Vector3i.from(1, 2, 3);
        }
        if (type == Vector2f.class) {
            return Vector2f.from(1f, 2f);
        }
        if (type == NbtMap.class) {
            return NbtMap.EMPTY;
        }
        if (type == OptionalBoolean.class) {
            return OptionalBoolean.empty();
        }
        if (type == byte[].class) {
            return new byte[0];
        }
        if (type.isArray()) {
            // Empty, not null: a serializer that reaches an array reads its length, so null is the one
            // value guaranteed to throw. ItemStackRequest holds both of its lists as arrays and was
            // never encoded until PlayerAuthInput started writing the request whenever it is present.
            return Array.newInstance(type.getComponentType(), 0);
        }
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            return null;
        }
        // A plain data holder (EduSharedUriResource, NetworkPermissions, ...): build it through
        // whichever constructor we can satisfy, then fill its own fields the same way.
        return construct(type, depth);
    }

    private static Object construct(Class<?> type, int depth) {
        if (depth >= 3) {
            return null;
        }
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        // Fewest arguments first: the less we have to invent, the likelier it is to be valid.
        java.util.Arrays.sort(constructors, java.util.Comparator.comparingInt(Constructor::getParameterCount));

        for (Constructor<?> constructor : constructors) {
            try {
                constructor.setAccessible(true);
                Class<?>[] parameters = constructor.getParameterTypes();
                Object[] arguments = new Object[parameters.length];
                for (int i = 0; i < parameters.length; i++) {
                    arguments[i] = parameters[i].isPrimitive() ? primitive(parameters[i]) : valueFor(parameters[i], depth + 1);
                }
                Object created = constructor.newInstance(arguments);
                populate(created, depth + 1);
                return created;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next constructor.
            }
        }
        return null;
    }

    /**
     * Avoids the sentinel constants. Enums here overwhelmingly start with a {@code NONE} / {@code
     * INVALID} member that has no wire representation at all — {@code ResourcePackClientResponse}
     * writes {@code ordinal() - 1}, so picking {@code NONE} encodes -1 and the packet looks broken
     * when it is only the test's choice that was.
     */
    private static Object enumConstant(Class<?> type) {
        Object[] constants = type.getEnumConstants();
        if (constants.length == 0) {
            return null;
        }
        for (Object constant : constants) {
            String name = ((Enum<?>) constant).name();
            if (!name.equals("NONE") && !name.equals("INVALID") && !name.equals("UNKNOWN")
                    && !name.equals("UNDEFINED") && !name.equals("ANY")) {
                return constant;
            }
        }
        return constants[0];
    }

    private static Object primitive(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return 'x';
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }
}
