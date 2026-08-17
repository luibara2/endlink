package org.endstone.proxy.palette;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.common.DefinitionRegistry;

import java.util.List;

/**
 * Translates item network ids between one backend's registry and the union registry the client was
 * given at login.
 *
 * <p>The translation costs nothing per packet, because it rides on the codec's existing decode step.
 * Every serializer writes an item as {@code definition.getRuntimeId()} of the decoded object and
 * never consults a registry on the way out (see {@code BedrockCodecHelper_v2168#writeItemInstance});
 * only decoding looks an id up. So installing an intentionally lopsided registry on each side is
 * enough to renumber every item in both directions:</p>
 *
 * <ul>
 *   <li>the <b>backend</b> session decodes with {@link #backendSide()}, which maps a backend id to a
 *       definition carrying the <em>client</em> id — so re-encoding to the client emits client ids;</li>
 *   <li>the <b>client</b> session decodes with {@link #clientSide()}, which maps a client id to a
 *       definition carrying the <em>backend</em> id — so re-encoding to the backend emits backend ids.</li>
 * </ul>
 *
 * <p>An id with no counterpart passes through unchanged rather than becoming air: the item is real on
 * the side that sent it, and a wrong texture is a far smaller failure than a slot the two ends
 * disagree about. {@link #unmappedFromBackend()} counts those so the cause can be reported once.</p>
 */
public final class ItemPaletteMapping {
    private final DefinitionRegistry<ItemDefinition> backendSide;
    private final DefinitionRegistry<ItemDefinition> clientSide;
    private final List<String> itemsMissingFromClient;
    private final boolean identity;

    private ItemPaletteMapping(
            DefinitionRegistry<ItemDefinition> backendSide,
            DefinitionRegistry<ItemDefinition> clientSide,
            List<String> itemsMissingFromClient,
            boolean identity
    ) {
        this.backendSide = backendSide;
        this.clientSide = clientSide;
        this.itemsMissingFromClient = List.copyOf(itemsMissingFromClient);
        this.identity = identity;
    }

    /**
     * Builds the mapping between one backend's items and the client's union registry.
     *
     * @param backendItems the backend's own registry, as it sent it
     * @param clientItems  the union registry the client was given at login
     */
    public static ItemPaletteMapping between(List<ItemDefinition> backendItems, List<ItemDefinition> clientItems) {
        Int2ObjectMap<ItemDefinition> toClient = new Int2ObjectOpenHashMap<>();
        Int2ObjectMap<ItemDefinition> toBackend = new Int2ObjectOpenHashMap<>();
        java.util.Map<String, ItemDefinition> toClientByName = new java.util.HashMap<>();
        java.util.Map<String, ItemDefinition> toBackendByName = new java.util.HashMap<>();
        java.util.Map<String, ItemDefinition> clientByIdentifier = new java.util.HashMap<>();
        for (ItemDefinition clientItem : clientItems) {
            clientByIdentifier.put(clientItem.getIdentifier(), clientItem);
        }

        List<String> missing = new java.util.ArrayList<>();
        boolean identity = true;
        for (ItemDefinition backendItem : backendItems) {
            ItemDefinition clientItem = clientByIdentifier.get(backendItem.getIdentifier());
            if (clientItem == null) {
                // The client's registry predates this backend learning the item: it was not in the
                // union when this player logged in. Nothing can be done for them until they rejoin.
                missing.add(backendItem.getIdentifier());
                continue;
            }
            if (clientItem.getRuntimeId() != backendItem.getRuntimeId()) {
                identity = false;
            }
            ItemDefinition clientNumbered = rebrand(backendItem, clientItem.getRuntimeId());
            ItemDefinition backendNumbered = rebrand(clientItem, backendItem.getRuntimeId());
            toClient.put(backendItem.getRuntimeId(), clientNumbered);
            toBackend.put(clientItem.getRuntimeId(), backendNumbered);
            // Recipes name their ingredients rather than numbering them
            // ({@code BedrockCodecHelper_v2168#readItemDescriptor}), so both sides need the same
            // translation reachable by identifier.
            toClientByName.put(backendItem.getIdentifier(), clientNumbered);
            toBackendByName.put(clientItem.getIdentifier(), backendNumbered);
        }

        return new ItemPaletteMapping(
                new MappedRegistry(toClient, toClientByName),
                new MappedRegistry(toBackend, toBackendByName),
                missing,
                identity && missing.isEmpty()
        );
    }

    private static ItemDefinition rebrand(ItemDefinition source, int runtimeId) {
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

    /** Install on the backend session: backend id in, client id out. */
    public DefinitionRegistry<ItemDefinition> backendSide() {
        return backendSide;
    }

    /** Install on the client session: client id in, backend id out. */
    public DefinitionRegistry<ItemDefinition> clientSide() {
        return clientSide;
    }

    /** Items this backend has that the client's registry does not, and so cannot render correctly. */
    public List<String> unmappedFromBackend() {
        return itemsMissingFromClient;
    }

    /** True when every id already agrees, so installing this mapping would change nothing. */
    public boolean isIdentity() {
        return identity;
    }

    private record PassthroughDefinition(String identifier, int runtimeId) implements ItemDefinition {
        @Override
        public String getIdentifier() {
            return identifier;
        }

        @Override
        public int getRuntimeId() {
            return runtimeId;
        }

        @Override
        public boolean isComponentBased() {
            return false;
        }
    }

    private record MappedRegistry(
            Int2ObjectMap<ItemDefinition> byRuntimeId,
            java.util.Map<String, ItemDefinition> byIdentifier
    ) implements DefinitionRegistry<ItemDefinition> {
        @Override
        public ItemDefinition getDefinition(int runtimeId) {
            if (runtimeId == 0) {
                return ItemDefinition.AIR;
            }
            ItemDefinition mapped = byRuntimeId.get(runtimeId);
            if (mapped != null) {
                return mapped;
            }
            return new PassthroughDefinition("minecraft:unmapped_" + runtimeId, runtimeId);
        }

        /**
         * Recipes reference items by name. Never null and never throwing: the encoder writes
         * {@code getItemId().getIdentifier()} straight back out, so an unknown name has to survive as
         * itself or the whole CraftingData packet fails to re-encode.
         */
        @Override
        public ItemDefinition getDefinition(String identifier) {
            ItemDefinition mapped = byIdentifier.get(identifier);
            if (mapped != null) {
                return mapped;
            }
            return new PassthroughDefinition(identifier == null ? "" : identifier, 0);
        }

        @Override
        public boolean isRegistered(ItemDefinition definition) {
            return definition != null;
        }
    }
}
