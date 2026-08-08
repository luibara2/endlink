package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.codec.EntityDataTypeMap;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataType;
import org.cloudburstmc.protocol.common.util.TypeMap;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Guards the 1.26.40 &harr; 1.26.30 hop at the level where cross-protocol translation actually
 * happens: the {@link TypeMap}s.
 *
 * <p>The proxy decodes with one side's codec and re-encodes with the other's, so a wire id is never
 * copied across — it is resolved to an enum constant by the source codec and looked up again by the
 * target. That is what makes shifted ids (1.26.40 inserted a particle at 102 and an entity flag at
 * 130) translate for free. It also means the hop breaks the moment one side can produce a constant
 * the other has never heard of: {@link TypeMap#getId} throws
 * {@code IllegalArgumentException: No id found for X} and the packet dies mid-encode.</p>
 *
 * <p>Clientbound is safe by construction — {@code TypeMap.Builder.insert} refuses to overwrite an
 * occupied slot, so 1.26.40 could only add to 1.26.30's maps, never replace an entry out of them.
 * Serverbound is the direction that needs checking, and it is checked here rather than reasoned
 * about, because the maps descend through twenty intermediate codecs.</p>
 */
class CrossProtocolTypeMapTest {

    /**
     * Constants that exist only in 1.26.40 and are unreachable serverbound, so they can never reach
     * a 1.26.30 backend's encoder. Each is a value only a server sends to a client.
     */
    private static final Map<String, List<String>> CLIENTBOUND_ONLY_ADDITIONS = Map.of(
            "PARTICLE_TYPES", List.of("ORANGE_POPLAR_LEAVES", "RED_POPLAR_LEAVES", "YELLOW_POPLAR_LEAVES"),
            "ENTITY_FLAGS", List.of("NOT_PICKABLE_FROM_INSIDE"),
            "SOUND_EVENTS", List.of("MOUNT", "DISMOUNT", "STRAW_BED_BREAK_LEAVE")
    );

    @Test
    void everyTypeA1_26_40ClientCanSendIsEncodableFor1_26_30() {
        List<String> unexpected = new ArrayList<>();

        for (String mapName : List.of("PARTICLE_TYPES", "ENTITY_FLAGS", "SOUND_EVENTS",
                "ITEM_STACK_REQUEST_TYPES", "CONTAINER_SLOT_TYPES", "PLAYER_ABILITIES",
                "TEXT_PROCESSING_ORIGINS")) {
            TypeMap<Object> modern = typeMap(Bedrock_v2168.class, mapName);
            TypeMap<Object> legacy = typeMap(Bedrock_v1001.class, mapName);
            if (modern == null || legacy == null) {
                continue;
            }

            List<String> allowed = CLIENTBOUND_ONLY_ADDITIONS.getOrDefault(mapName, List.of());
            Map<String, Integer> missing = new LinkedHashMap<>();
            modern.forEach((id, value) -> {
                if (legacy.getIdUnsafe(value) == -1) {
                    missing.put(String.valueOf(value), id);
                }
            });

            missing.forEach((value, id) -> {
                if (!allowed.contains(value)) {
                    unexpected.add(mapName + "[" + id + "] = " + value);
                }
            });
        }

        if (!unexpected.isEmpty()) {
            throw new AssertionError("""
                    1.26.40 can produce values that a 1.26.30 codec cannot encode. Each of these \
                    throws "No id found for X" mid-encode if it ever travels serverbound. Either \
                    confirm the value is clientbound-only and add it to CLIENTBOUND_ONLY_ADDITIONS, \
                    or translate it in ModernClientTo1001Translator:
                    """ + String.join("\n  ", unexpected));
        }
    }

    /**
     * The entity data map is checked separately: it is keyed by {@link EntityDataType} rather than
     * by an enum constant, and an unknown key is worse than a throw. {@code writeEntityData} calls
     * {@code entityData.fromType(key)} and dereferences the result without a null check — including
     * inside its own catch block, where the error message calls {@code definition.getId()}. A type
     * the target codec does not know therefore raises a {@link NullPointerException} from inside an
     * exception handler, which is a genuinely confusing way to lose a session.
     */
    @Test
    void everyEntityDataTypeA1_26_40ClientCanSendIsEncodableFor1_26_30() {
        EntityDataTypeMap modern = entityDataMap(Bedrock_v2168.class);
        EntityDataTypeMap legacy = entityDataMap(Bedrock_v1001.class);

        List<String> missing = new ArrayList<>();
        for (EntityDataType<?> type : declaredEntityDataTypes(modern)) {
            if (legacy.fromType(type) == null) {
                missing.add(type.toString());
            }
        }

        // UNKNOWN_HORSE_INT_25 is new in 1.26.40 and clientbound-only, like all entity metadata:
        // SetEntityData and AddEntity/AddPlayer are server-to-client packets, so a 1.26.30 backend
        // is never asked to encode one.
        missing.remove("UNKNOWN_HORSE_INT_25");

        if (!missing.isEmpty()) {
            throw new AssertionError(
                    "1.26.40 entity data types with no 1.26.30 equivalent: " + String.join(", ", missing));
        }
    }

    /**
     * The reverse direction, and the one that runs constantly: everything a 1.26.30 backend sends
     * has to be encodable for a 1.26.40 client. A gap here breaks ordinary play rather than an edge
     * case.
     */
    @Test
    void everyTypeA1_26_30BackendCanSendIsEncodableFor1_26_40() {
        List<String> missing = new ArrayList<>();

        for (String mapName : List.of("PARTICLE_TYPES", "ENTITY_FLAGS", "SOUND_EVENTS",
                "ITEM_STACK_REQUEST_TYPES", "CONTAINER_SLOT_TYPES", "PLAYER_ABILITIES",
                "TEXT_PROCESSING_ORIGINS")) {
            TypeMap<Object> modern = typeMap(Bedrock_v2168.class, mapName);
            TypeMap<Object> legacy = typeMap(Bedrock_v1001.class, mapName);
            if (modern == null || legacy == null) {
                continue;
            }
            legacy.forEach((id, value) -> {
                if (modern.getIdUnsafe(value) == -1) {
                    missing.add(mapName + "[" + id + "] = " + value);
                }
            });
        }

        EntityDataTypeMap modernEntityData = entityDataMap(Bedrock_v2168.class);
        for (EntityDataType<?> type : declaredEntityDataTypes(entityDataMap(Bedrock_v1001.class))) {
            if (modernEntityData.fromType(type) == null) {
                missing.add("ENTITY_DATA " + type.toString());
            }
        }

        if (!missing.isEmpty()) {
            throw new AssertionError("""
                    A 1.26.30 backend can send values a 1.26.40 client codec cannot encode, which \
                    breaks the common deployment (new client, lagging Endstone backend):
                    """ + String.join("\n  ", missing));
        }
    }

    @SuppressWarnings("unchecked")
    private static TypeMap<Object> typeMap(Class<?> codec, String name) {
        Object value = staticField(codec, name);
        return value instanceof TypeMap ? (TypeMap<Object>) value : null;
    }

    private static EntityDataTypeMap entityDataMap(Class<?> codec) {
        Object value = staticField(codec, "ENTITY_DATA");
        if (value == null) {
            throw new AssertionError("no ENTITY_DATA on " + codec.getSimpleName());
        }
        return (EntityDataTypeMap) value;
    }

    /** These maps are {@code protected static} and inherited, so walk the hierarchy to find them. */
    private static Object staticField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(null);
            } catch (NoSuchFieldException ignored) {
                // keep walking
            } catch (IllegalAccessException e) {
                throw new AssertionError("cannot read " + name + " on " + current.getSimpleName(), e);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<EntityDataType<?>> declaredEntityDataTypes(EntityDataTypeMap map) {
        try {
            Field field = EntityDataTypeMap.class.getDeclaredField("typeDefinitionMap");
            field.setAccessible(true);
            Map<EntityDataType<?>, ?> definitions = (Map<EntityDataType<?>, ?>) field.get(map);
            return new ArrayList<>(definitions.keySet());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot enumerate entity data types", e);
        }
    }
}
