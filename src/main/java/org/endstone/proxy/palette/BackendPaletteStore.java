package org.endstone.proxy.palette;

import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every backend's registries, learned from live sessions and remembered across restarts.
 *
 * <p>A client can only be given the union of all backends' content at the moment it logs in, which
 * is before it has been anywhere. The proxy therefore learns each backend's palette the first time
 * anyone visits it and writes it to disk, so the next login already knows about backends this
 * session has not touched. The cost of that design is one stale visit: after a backend's addons
 * change, the first player to go there sees the old registry until they rejoin. That is reported,
 * not silent — see {@code ItemPaletteMapping#unmappedFromBackend()}.</p>
 *
 * <p>Nothing here is a security boundary: the cache holds only what backends already send every
 * client at login. It is still read with a size limit, because a corrupt file should fail the load,
 * not the process.</p>
 */
public final class BackendPaletteStore {
    private static final long MAX_CACHE_BYTES = 64L * 1024 * 1024;
    private static final String BACKENDS = "backends";
    private static final String ITEMS = "items";
    private static final String ENTITY_IDENTIFIERS = "entityIdentifiers";
    private static final String ENTITY_PROPERTIES = "entityProperties";
    private static final String BLOCK_PROPERTIES = "blockProperties";
    private static final String BLOCK_IDS_HASHED = "blockIdsHashed";
    private static final String BLOCK_PROPERTIES_DATA = "data";
    private static final String NAME = "name";
    private static final String RUNTIME_ID = "id";
    private static final String COMPONENT_BASED = "componentBased";
    private static final String VERSION = "version";
    private static final String COMPONENT_DATA = "componentData";

    private final Path cacheFile;
    private final Map<String, BackendPalette> palettes = new LinkedHashMap<>();
    private final Set<String> reported = new java.util.HashSet<>();
    private final boolean enabled;

    private BackendPaletteStore(Path cacheFile, boolean enabled) {
        this.cacheFile = cacheFile;
        this.enabled = enabled;
    }

    /** A store that learns nothing and unions nothing; every backend keeps its own ids. */
    public static BackendPaletteStore disabled() {
        return new BackendPaletteStore(null, false);
    }

    /** Loads the cache at {@code cacheFile}, or starts empty when it does not exist or is unreadable. */
    public static BackendPaletteStore load(Path cacheFile) {
        BackendPaletteStore store = new BackendPaletteStore(cacheFile, true);
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) {
            return store;
        }
        try (InputStream in = Files.newInputStream(cacheFile);
             NBTInputStream reader = NbtUtils.createGZIPReader(in, MAX_CACHE_BYTES)) {
            NbtMap root = (NbtMap) reader.readTag();
            store.readFrom(root);
        } catch (Exception e) {
            System.out.printf(
                    "Could not read the backend palette cache %s (%s); it will be relearned as players visit each backend.%n",
                    cacheFile, e.getMessage()
            );
            store.palettes.clear();
        }
        return store;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public synchronized Set<String> knownBackends() {
        return new TreeSet<>(palettes.keySet());
    }

    public synchronized BackendPalette palette(String backendName) {
        return palettes.get(backendName);
    }

    /** Every known palette except {@code backendName}'s, in a stable order. */
    public synchronized List<BackendPalette> otherPalettes(String backendName) {
        List<BackendPalette> others = new ArrayList<>();
        for (String name : new TreeSet<>(palettes.keySet())) {
            if (!name.equals(backendName)) {
                others.add(palettes.get(name));
            }
        }
        return others;
    }

    /**
     * Records a backend's item registry.
     *
     * @return true when this changed what was known, so a player who logged in earlier may be
     *         holding a stale union
     */
    public synchronized boolean learnItems(String backendName, List<ItemDefinition> items) {
        if (!enabled || backendName == null || items == null || items.isEmpty()) {
            return false;
        }
        BackendPalette existing = palettes.getOrDefault(backendName, BackendPalette.empty(backendName));
        if (sameItems(existing.items(), items)) {
            return false;
        }
        palettes.put(backendName, existing.withItems(items));
        save();
        return true;
    }

    public synchronized boolean learnEntityIdentifiers(String backendName, NbtMap identifiers) {
        if (!enabled || backendName == null || identifiers == null) {
            return false;
        }
        BackendPalette existing = palettes.getOrDefault(backendName, BackendPalette.empty(backendName));
        if (identifiers.equals(existing.entityIdentifiers())) {
            return false;
        }
        palettes.put(backendName, existing.withEntityIdentifiers(identifiers));
        save();
        return true;
    }

    public synchronized boolean learnBlockProperties(String backendName, List<BlockPropertyData> blockProperties) {
        if (!enabled || backendName == null || blockProperties == null || blockProperties.isEmpty()) {
            return false;
        }
        BackendPalette existing = palettes.getOrDefault(backendName, BackendPalette.empty(backendName));
        if (blockProperties.equals(existing.blockProperties())) {
            return false;
        }
        palettes.put(backendName, existing.withBlockProperties(blockProperties));
        save();
        return true;
    }

    /**
     * Records whether a backend hashes its block network ids.
     *
     * <p>Unlike the rest of the store this is learned even when the palette itself is not shared,
     * because it is exactly the backends whose blocks cannot be shared that this has to be known
     * for. See {@link BackendPalette#withBlockIdsHashed}.</p>
     */
    public synchronized boolean learnBlockIdsHashed(String backendName, boolean blockIdsHashed) {
        if (!enabled || backendName == null) {
            return false;
        }
        BackendPalette existing = palettes.getOrDefault(backendName, BackendPalette.empty(backendName));
        if (existing.blockIdsHashed() != null && existing.blockIdsHashed() == blockIdsHashed) {
            return false;
        }
        palettes.put(backendName, existing.withBlockIdsHashed(blockIdsHashed));
        save();
        return true;
    }

    /** Whether the named backend hashes block ids, or null if it has never been seen. */
    public synchronized Boolean blockIdsHashed(String backendName) {
        BackendPalette palette = backendName == null ? null : palettes.get(backendName);
        return palette == null ? null : palette.blockIdsHashed();
    }

    public synchronized boolean learnEntityProperty(String backendName, NbtMap property) {
        if (!enabled || backendName == null || property == null || property.isEmpty()) {
            return false;
        }
        BackendPalette existing = palettes.getOrDefault(backendName, BackendPalette.empty(backendName));
        BackendPalette updated = existing.withEntityProperty(property);
        if (updated.entityProperties().equals(existing.entityProperties())) {
            return false;
        }
        palettes.put(backendName, updated);
        save();
        return true;
    }

    private static boolean sameItems(List<ItemDefinition> a, List<ItemDefinition> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            ItemDefinition left = a.get(i);
            ItemDefinition right = b.get(i);
            if (left.getRuntimeId() != right.getRuntimeId()
                    || !left.getIdentifier().equals(right.getIdentifier())) {
                return false;
            }
        }
        return true;
    }

    private void save() {
        if (cacheFile == null) {
            return;
        }
        Path temporary = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        try {
            Path parent = cacheFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(temporary);
                 NBTOutputStream writer = NbtUtils.createGZIPWriter(out)) {
                writer.writeTag(writeTo());
            }
            // Replace in one step: a half-written cache read back at the next start would be a
            // wrong union, which is worse than no cache at all.
            Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.printf("Could not write the backend palette cache %s: %s%n", cacheFile, e.getMessage());
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    private NbtMap writeTo() {
        NbtMapBuilder backends = NbtMap.builder();
        for (Map.Entry<String, BackendPalette> entry : palettes.entrySet()) {
            BackendPalette palette = entry.getValue();
            List<NbtMap> items = new ArrayList<>(palette.items().size());
            for (ItemDefinition item : palette.items()) {
                NbtMapBuilder builder = NbtMap.builder()
                        .putString(NAME, item.getIdentifier())
                        .putInt(RUNTIME_ID, item.getRuntimeId())
                        .putBoolean(COMPONENT_BASED, item.isComponentBased());
                if (item instanceof SimpleItemDefinition simple) {
                    builder.putInt(VERSION, simple.getVersion() == null ? 0 : simple.getVersion().ordinal());
                    if (simple.getComponentData() != null) {
                        builder.putCompound(COMPONENT_DATA, simple.getComponentData());
                    }
                }
                items.add(builder.build());
            }
            List<NbtMap> blocks = new ArrayList<>(palette.blockProperties().size());
            for (BlockPropertyData block : palette.blockProperties()) {
                blocks.add(NbtMap.builder()
                        .putString(NAME, block.getName())
                        .putCompound(BLOCK_PROPERTIES_DATA, block.getProperties() == null
                                ? NbtMap.EMPTY : block.getProperties())
                        .build());
            }
            NbtMapBuilder backend = NbtMap.builder()
                    .putList(ITEMS, NbtType.COMPOUND, items)
                    .putList(BLOCK_PROPERTIES, NbtType.COMPOUND, blocks)
                    .putList(ENTITY_PROPERTIES, NbtType.COMPOUND, palette.entityProperties());
            // Written only once seen, so "never visited" stays distinguishable from "visited and
            // does not hash" — the switcher treats those two cases differently.
            if (palette.blockIdsHashed() != null) {
                backend.putBoolean(BLOCK_IDS_HASHED, palette.blockIdsHashed());
            }
            if (palette.entityIdentifiers() != null) {
                backend.putCompound(ENTITY_IDENTIFIERS, palette.entityIdentifiers());
            }
            backends.putCompound(entry.getKey(), backend.build());
        }
        return NbtMap.builder().putCompound(BACKENDS, backends.build()).build();
    }

    private void readFrom(NbtMap root) {
        NbtMap backends = root == null ? null : root.getCompound(BACKENDS, NbtMap.EMPTY);
        if (backends == null) {
            return;
        }
        ItemVersion[] versions = ItemVersion.values();
        for (String backendName : backends.keySet()) {
            NbtMap backend = backends.getCompound(backendName);
            List<ItemDefinition> items = new ArrayList<>();
            for (NbtMap item : backend.getList(ITEMS, NbtType.COMPOUND, List.of())) {
                int versionOrdinal = item.getInt(VERSION, 0);
                ItemVersion version = versionOrdinal >= 0 && versionOrdinal < versions.length
                        ? versions[versionOrdinal]
                        : ItemVersion.LEGACY;
                items.add(new SimpleItemDefinition(
                        item.getString(NAME),
                        item.getInt(RUNTIME_ID),
                        version,
                        item.getBoolean(COMPONENT_BASED),
                        item.containsKey(COMPONENT_DATA) ? item.getCompound(COMPONENT_DATA) : null
                ));
            }
            List<BlockPropertyData> blocks = new ArrayList<>();
            for (NbtMap block : backend.getList(BLOCK_PROPERTIES, NbtType.COMPOUND, List.of())) {
                blocks.add(new BlockPropertyData(
                        block.getString(NAME),
                        block.getCompound(BLOCK_PROPERTIES_DATA, NbtMap.EMPTY)
                ));
            }
            palettes.put(backendName, new BackendPalette(
                    backendName,
                    items,
                    backend.containsKey(ENTITY_IDENTIFIERS) ? backend.getCompound(ENTITY_IDENTIFIERS) : null,
                    backend.getList(ENTITY_PROPERTIES, NbtType.COMPOUND, List.of()),
                    blocks,
                    backend.containsKey(BLOCK_IDS_HASHED) ? backend.getBoolean(BLOCK_IDS_HASHED) : null
            ));
        }
    }

    /**
     * True the first time this exact outcome is seen, so a per-join fact can be reported once.
     *
     * <p>Everything the palette does happens on every login of every player. Logging it per join
     * would bury the lines that need acting on — a stale backend, an overflowing registry — under
     * thousands of identical ones. Keying on the outcome means a genuine change still speaks up.</p>
     */
    public synchronized boolean firstReportOf(String key) {
        return reported.add(key);
    }

    /** One line describing what is known, for the startup banner. */
    public synchronized String describe() {
        if (!enabled) {
            return "cross-backend palette off";
        }
        if (palettes.isEmpty()) {
            return "no backend palettes learned yet";
        }
        StringBuilder builder = new StringBuilder();
        for (String backendName : new TreeSet<>(palettes.keySet())) {
            BackendPalette palette = palettes.get(backendName);
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(backendName)
                    .append(": ")
                    .append(palette.items().size())
                    .append(" items, ")
                    .append(EntityPalettes.idList(palette.entityIdentifiers()).size())
                    .append(" entities");
        }
        return builder.toString();
    }
}
