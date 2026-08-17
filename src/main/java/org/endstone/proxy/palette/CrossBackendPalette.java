package org.endstone.proxy.palette;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One player's view of every backend's content, decided at their login and fixed for their session.
 *
 * <p>Bedrock builds its item registry and its entity identifier list once, when the level starts,
 * and ignores both packets afterwards. Endlink switches backends without a re-login — that is the
 * point of it — so whatever the client is told at login is what it will still believe on the fourth
 * backend it visits. Sending it the union of every backend the proxy knows about is what makes the
 * seamless switch render correctly: the client holds definitions for content it has not met yet,
 * and {@link ItemPaletteMapping} renumbers ids per backend on the way past.</p>
 *
 * <p>The union keeps the joining backend's own ids unchanged and appends foreign items above them,
 * so the common case — one backend, or several with identical content — is an identity mapping that
 * costs nothing.</p>
 */
public final class CrossBackendPalette {
    /**
     * Item network ids are written as a signed 16-bit little-endian short
     * ({@code ItemComponentSerializer_v776}), so the union cannot number past this and stay decodable.
     */
    private static final int MAX_ITEM_RUNTIME_ID = Short.MAX_VALUE;

    private final BackendPaletteStore store;
    private List<ItemDefinition> clientItems;
    private List<BlockPropertyData> clientBlockProperties;
    private NbtMap clientEntityIdentifiers;
    private final Set<String> sentEntityPropertyTypes = new HashSet<>();
    private final Set<String> reportedMissingItems = new HashSet<>();

    public CrossBackendPalette(BackendPaletteStore store) {
        this.store = store == null ? BackendPaletteStore.disabled() : store;
    }

    public boolean isEnabled() {
        return store.isEnabled();
    }

    /** True once the client has been sent its item registry; after this it cannot be changed. */
    public boolean hasClientItems() {
        return clientItems != null;
    }

    public List<ItemDefinition> clientItems() {
        return clientItems;
    }

    public BackendPaletteStore store() {
        return store;
    }

    /**
     * Builds the item registry to send this client: {@code backendItems} as they are, plus every item
     * any other known backend has that is missing from them.
     */
    public List<ItemDefinition> buildClientItems(String backendName, List<ItemDefinition> backendItems) {
        Map<String, ItemDefinition> union = new LinkedHashMap<>();
        int nextRuntimeId = 0;
        for (ItemDefinition item : backendItems) {
            union.put(item.getIdentifier(), item);
            nextRuntimeId = Math.max(nextRuntimeId, item.getRuntimeId());
        }

        int added = 0;
        int skipped = 0;
        for (BackendPalette other : store.otherPalettes(backendName)) {
            for (ItemDefinition item : other.items()) {
                if (union.containsKey(item.getIdentifier())) {
                    continue;
                }
                if (nextRuntimeId >= MAX_ITEM_RUNTIME_ID) {
                    skipped++;
                    continue;
                }
                union.put(item.getIdentifier(), withRuntimeId(item, ++nextRuntimeId));
                added++;
            }
        }
        if (skipped > 0 && store.firstReportOf("overflow:" + backendName + ":" + skipped)) {
            System.out.printf(
                    "WARNING: the combined item registry of all backends does not fit in Bedrock's 16-bit "
                            + "item ids; %d item(s) were left out and will render wrong away from their own backend.%n",
                    skipped
            );
        }
        if (added > 0 && store.firstReportOf("items:" + backendName + ":" + added + "/" + union.size())) {
            System.out.printf(
                    "Extended the item registry for a client joining %s with %d item(s) from other backends "
                            + "(%d total), so switching backends keeps their textures.%n",
                    backendName, added, union.size()
            );
        }
        this.clientItems = List.copyOf(union.values());
        return clientItems;
    }

    private static ItemDefinition withRuntimeId(ItemDefinition source, int runtimeId) {
        if (source instanceof SimpleItemDefinition simple) {
            return new SimpleItemDefinition(
                    simple.getIdentifier(),
                    runtimeId,
                    simple.getVersion(),
                    simple.isComponentBased(),
                    simple.getComponentData()
            );
        }
        return new SimpleItemDefinition(source.getIdentifier(), runtimeId, source.isComponentBased());
    }

    /**
     * The custom block definitions to send this client: the joining backend's, plus every other
     * one's.
     *
     * <p>Blocks need no id translation, unlike items — a modern backend hashes a block's runtime id
     * from its state, so the same block is the same number everywhere ({@code blockNetworkIdsHashed}
     * in StartGame, which {@link #warnIfBlockIdsNotHashed} checks). What the client does need is the
     * definition itself: a hashed id it has no block for cannot be drawn, however correct it is.
     * StartGame carries those definitions, and StartGame is read once at level init.</p>
     */
    /**
     * Applies the block half of the palette to a backend's StartGame before it reaches the client:
     * learns this backend's custom blocks, replaces the list with the union of every backend's, and
     * clears the block registry checksum when — and only when — that union added something.
     *
     * <p>The checksum is why this has to be one operation. StartGame carries a checksum over the
     * server's block registry and the client verifies its own palette against it; a client given a
     * deliberately larger palette than the backend described cannot match it and disconnects with
     * {@code BlockMismatch} before a single chunk renders. Zero is the documented opt-out (see
     * {@code CrossProtocolStartGameFixups}, which does the same for a cross-version hop). Left intact
     * when nothing was added, so a genuinely corrupt palette is still caught on an ordinary join.</p>
     *
     * @return true when the packet was changed
     */
    public boolean applyToStartGame(String backendName, StartGamePacket startGame) {
        List<BlockPropertyData> backendBlocks = List.copyOf(startGame.getBlockProperties());
        store.learnBlockProperties(backendName, backendBlocks);
        warnIfBlockIdsNotHashed(backendName, startGame.isBlockNetworkIdsHashed());

        List<BlockPropertyData> union = buildClientBlockProperties(backendName, backendBlocks);
        if (union.size() == backendBlocks.size()) {
            return false;
        }
        startGame.getBlockProperties().clear();
        startGame.getBlockProperties().addAll(union);
        startGame.setBlockRegistryChecksum(0L);
        return true;
    }

    public List<BlockPropertyData> buildClientBlockProperties(
            String backendName,
            List<BlockPropertyData> backendBlocks
    ) {
        Map<String, BlockPropertyData> union = new LinkedHashMap<>();
        for (BlockPropertyData block : backendBlocks) {
            union.put(block.getName(), block);
        }
        int before = union.size();
        for (BackendPalette other : store.otherPalettes(backendName)) {
            for (BlockPropertyData block : other.blockProperties()) {
                union.putIfAbsent(block.getName(), block);
            }
        }
        int added = union.size() - before;
        if (added > 0 && store.firstReportOf("blocks:" + backendName + ":" + added)) {
            System.out.printf(
                    "Extended the block registry for a client joining %s with %d custom block(s) from other "
                            + "backends, so they render after a switch.%n",
                    backendName, added
            );
        }
        this.clientBlockProperties = List.copyOf(union.values());
        return clientBlockProperties;
    }

    public List<BlockPropertyData> clientBlockProperties() {
        return clientBlockProperties;
    }

    /**
     * Custom blocks can only be shared between backends while their ids are hashed from the block
     * state. A backend numbering them by palette order gives the same block a different id on every
     * world, and nothing the proxy can do at login fixes that.
     */
    public void warnIfBlockIdsNotHashed(String backendName, boolean blockNetworkIdsHashed) {
        if (blockNetworkIdsHashed || !store.firstReportOf("unhashedBlocks:" + backendName)) {
            return;
        }
        System.out.printf(
                "WARNING: backend %s numbers block ids by palette order rather than hashing them. Its custom "
                        + "blocks will render as the wrong block for players who arrived from another backend, "
                        + "and the proxy cannot correct it.%n",
                backendName
        );
    }

    /** The entity identifier list to send this client: the joining backend's, plus every other one's. */
    public NbtMap buildClientEntityIdentifiers(String backendName, NbtMap backendIdentifiers) {
        NbtMap merged = backendIdentifiers;
        int before = EntityPalettes.idList(backendIdentifiers).size();
        for (BackendPalette other : store.otherPalettes(backendName)) {
            merged = EntityPalettes.mergeIdentifiers(merged, other.entityIdentifiers());
        }
        int added = EntityPalettes.idList(merged).size() - before;
        if (added > 0 && store.firstReportOf("entities:" + backendName + ":" + added)) {
            System.out.printf(
                    "Extended the entity list for a client joining %s with %d entity type(s) from other "
                            + "backends, so they stay visible after a switch.%n",
                    backendName, added
            );
        }
        this.clientEntityIdentifiers = merged;
        return merged;
    }

    public NbtMap clientEntityIdentifiers() {
        return clientEntityIdentifiers;
    }

    /** Remembers an entity property list already sent to the client; returns false if it is a repeat. */
    public boolean markEntityPropertySent(NbtMap property) {
        return sentEntityPropertyTypes.add(EntityPalettes.entityPropertyType(property));
    }

    /** The entity property lists from other backends that this client has not been sent yet. */
    public List<NbtMap> pendingEntityProperties(String backendName) {
        List<NbtMap> pending = new ArrayList<>();
        for (BackendPalette other : store.otherPalettes(backendName)) {
            for (NbtMap property : other.entityProperties()) {
                if (sentEntityPropertyTypes.add(EntityPalettes.entityPropertyType(property))) {
                    pending.add(property);
                }
            }
        }
        return pending;
    }

    /**
     * The mapping to install for {@code backendName}, or null when the client has no registry yet.
     * Reports items this backend has that the client's registry does not — once per session per item.
     */
    public ItemPaletteMapping mappingFor(String backendName, List<ItemDefinition> backendItems) {
        if (clientItems == null || backendItems == null || backendItems.isEmpty()) {
            return null;
        }
        ItemPaletteMapping mapping = ItemPaletteMapping.between(backendItems, clientItems);
        List<String> missing = new ArrayList<>();
        for (String identifier : mapping.unmappedFromBackend()) {
            if (reportedMissingItems.add(identifier)) {
                missing.add(identifier);
            }
        }
        if (!missing.isEmpty()) {
            System.out.printf(
                    "WARNING: backend %s has %d item(s) the player's client does not know about, because they "
                            + "were not in any backend's cached registry when the player logged in: %s. They will "
                            + "show the wrong texture until the player rejoins; everyone who joins from now on "
                            + "gets them.%n",
                    backendName,
                    missing.size(),
                    String.join(", ", missing.subList(0, Math.min(missing.size(), 10)))
            );
        }
        return mapping;
    }
}
