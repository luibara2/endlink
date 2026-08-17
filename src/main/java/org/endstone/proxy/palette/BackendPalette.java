package org.endstone.proxy.palette;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;

import java.util.Collections;
import java.util.List;

/**
 * What one backend told a client about its content: the item registry it assigns network ids from,
 * the entity identifiers it can spawn, and the entity property lists that go with them.
 *
 * <p>All three are read by the client exactly once, at level init, and never again — which is the
 * whole reason this class exists. A seamless backend switch does not re-run level init, so the
 * client keeps whatever the <em>first</em> backend sent. Collecting each backend's palette lets the
 * proxy hand a joining client the union of all of them, and remap ids per backend afterwards.</p>
 */
public record BackendPalette(
        String backendName,
        List<ItemDefinition> items,
        NbtMap entityIdentifiers,
        List<NbtMap> entityProperties,
        List<BlockPropertyData> blockProperties
) {
    public BackendPalette {
        items = items == null ? List.of() : List.copyOf(items);
        entityProperties = entityProperties == null ? List.of() : List.copyOf(entityProperties);
        blockProperties = blockProperties == null ? List.of() : List.copyOf(blockProperties);
    }

    public static BackendPalette empty(String backendName) {
        return new BackendPalette(backendName, List.of(), null, List.of(), List.of());
    }

    public BackendPalette withItems(List<ItemDefinition> items) {
        return new BackendPalette(backendName, items, entityIdentifiers, entityProperties, blockProperties);
    }

    public BackendPalette withEntityIdentifiers(NbtMap entityIdentifiers) {
        return new BackendPalette(backendName, items, entityIdentifiers, entityProperties, blockProperties);
    }

    /**
     * Custom block definitions, as {@code StartGamePacket} carries them. Read by the client at level
     * init like the item registry, so a block a client was never told about cannot render however
     * correct its runtime id is.
     */
    public BackendPalette withBlockProperties(List<BlockPropertyData> blockProperties) {
        return new BackendPalette(backendName, items, entityIdentifiers, entityProperties, blockProperties);
    }

    /** Adds one entity property list, replacing any earlier list for the same entity type. */
    public BackendPalette withEntityProperty(NbtMap property) {
        if (property == null) {
            return this;
        }
        String type = EntityPalettes.entityPropertyType(property);
        List<NbtMap> merged = new java.util.ArrayList<>(entityProperties.size() + 1);
        for (NbtMap existing : entityProperties) {
            if (!EntityPalettes.entityPropertyType(existing).equals(type)) {
                merged.add(existing);
            }
        }
        merged.add(property);
        return new BackendPalette(backendName, items, entityIdentifiers, merged, blockProperties);
    }

    public boolean isEmpty() {
        return items.isEmpty() && entityIdentifiers == null && entityProperties.isEmpty()
                && blockProperties.isEmpty();
    }

    public List<NbtMap> entityPropertiesView() {
        return Collections.unmodifiableList(entityProperties);
    }
}
