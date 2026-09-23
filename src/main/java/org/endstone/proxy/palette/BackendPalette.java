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
        List<BlockPropertyData> blockProperties,
        Boolean blockIdsHashed,
        Boolean subChunkRequests
) {
    public BackendPalette {
        items = items == null ? List.of() : List.copyOf(items);
        entityProperties = entityProperties == null ? List.of() : List.copyOf(entityProperties);
        blockProperties = blockProperties == null ? List.of() : List.copyOf(blockProperties);
    }

    public static BackendPalette empty(String backendName) {
        return new BackendPalette(backendName, List.of(), null, List.of(), List.of(), null, null);
    }

    public BackendPalette withItems(List<ItemDefinition> items) {
        return new BackendPalette(backendName, items, entityIdentifiers, entityProperties, blockProperties, blockIdsHashed, subChunkRequests);
    }

    public BackendPalette withEntityIdentifiers(NbtMap entityIdentifiers) {
        return new BackendPalette(backendName, items, entityIdentifiers, entityProperties, blockProperties, blockIdsHashed, subChunkRequests);
    }

    /**
     * Whether this backend hashes block network ids, or null while it has never been seen.
     *
     * <p>The one fact about a backend that decides whether a player can be handed to it seamlessly.
     * A client reads its block-id scheme from the StartGame it logs in with and never again, so a
     * session that started on a hashing backend renders nothing on a palette-indexed one, and the
     * reverse. Persisted because the decision has to be made <em>before</em> the switch — the first
     * player to move after a restart cannot be the one who discovers it.</p>
     */
    public BackendPalette withBlockIdsHashed(boolean blockIdsHashed) {
        return new BackendPalette(backendName, items, entityIdentifiers, entityProperties, blockProperties, blockIdsHashed, subChunkRequests);
    }

    /**
     * Whether this backend asks clients to request terrain a sub-chunk at a time, or null while it has
     * never sent a chunk.
     *
     * <p>The second fact that decides whether a handoff can be seamless. BDS announces its chunks with
     * {@code requestSubChunks} and serves the {@code SubChunkRequest}s that follow; PowerNukkitX and
     * Geyser send every chunk whole and never answer one. A client learns request mode from the first
     * backend that uses it and keeps it for the whole session, so after a seamless switch to a
     * whole-chunk backend it asks for the sub-chunks around the player, waits on "Building terrain"
     * for answers that never come, and renders nothing. Persisted for the same reason as
     * {@link #withBlockIdsHashed}: the switch has to be decided before it happens.</p>
     */
    public BackendPalette withSubChunkRequests(boolean subChunkRequests) {
        return new BackendPalette(backendName, items, entityIdentifiers, entityProperties, blockProperties, blockIdsHashed, subChunkRequests);
    }

    /**
     * Custom block definitions, as {@code StartGamePacket} carries them. Read by the client at level
     * init like the item registry, so a block a client was never told about cannot render however
     * correct its runtime id is.
     */
    public BackendPalette withBlockProperties(List<BlockPropertyData> blockProperties) {
        return new BackendPalette(backendName, items, entityIdentifiers, entityProperties, blockProperties, blockIdsHashed, subChunkRequests);
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
        return new BackendPalette(backendName, items, entityIdentifiers, merged, blockProperties, blockIdsHashed, subChunkRequests);
    }

    public boolean isEmpty() {
        return items.isEmpty() && entityIdentifiers == null && entityProperties.isEmpty()
                && blockProperties.isEmpty();
    }

    public List<NbtMap> entityPropertiesView() {
        return Collections.unmodifiableList(entityProperties);
    }
}
