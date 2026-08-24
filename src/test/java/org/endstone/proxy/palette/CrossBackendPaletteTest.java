package org.endstone.proxy.palette;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.RecipeUnlockingRequirement;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.ShapedRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.DefaultDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemDescriptorWithCount;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seamless-switch failure this package exists for: a client keeps the item registry and entity
 * list of the first backend it joined, so a second backend's custom content renders as wrong
 * textures and invisible entities. These pin the two halves of the fix — a union registry at login,
 * and per-backend id translation afterwards.
 */
final class CrossBackendPaletteTest {
    private static final int VANILLA_MAX = 1300;

    private static ItemDefinition item(String identifier, int runtimeId) {
        return new SimpleItemDefinition(identifier, runtimeId, ItemVersion.DATA_DRIVEN, true, NbtMap.EMPTY);
    }

    /** hub: vanilla only. skygen: the same vanilla ids plus its own custom items above them. */
    private static List<ItemDefinition> hubItems() {
        return List.of(item("minecraft:stone", 1), item("minecraft:dirt", 2), item("hub:menu", VANILLA_MAX + 1));
    }

    private static List<ItemDefinition> skygenItems() {
        return List.of(
                item("minecraft:stone", 1),
                item("minecraft:dirt", 2),
                item("skygen:galaxy_sword", VANILLA_MAX + 1),
                item("skygen:10k", VANILLA_MAX + 2)
        );
    }

    private static CrossBackendPalette paletteKnowing(Path cacheFile, String backend, List<ItemDefinition> items) {
        BackendPaletteStore store = BackendPaletteStore.load(cacheFile);
        store.learnItems(backend, items);
        return new CrossBackendPalette(store);
    }

    @Test
    void aClientJoiningHubIsGivenSkygensItemsToo(@TempDir Path dir) {
        CrossBackendPalette palette = paletteKnowing(dir.resolve("palettes.nbt"), "skygen", skygenItems());

        List<ItemDefinition> clientItems = palette.buildClientItems("hub", hubItems());

        assertEquals(5, clientItems.size(), () -> "expected hub's 3 items plus skygen's 2 extras: " + clientItems);
        // hub's own ids must not move: they are what hub will keep sending.
        assertEquals(VANILLA_MAX + 1, idOf(clientItems, "hub:menu"));
        // skygen's extras are appended above them, so they cannot collide with hub:menu.
        assertTrue(idOf(clientItems, "skygen:galaxy_sword") > VANILLA_MAX + 1);
        assertTrue(idOf(clientItems, "skygen:10k") > VANILLA_MAX + 1);
        assertEquals(1, idOf(clientItems, "minecraft:stone"));
    }

    @Test
    void skygensOwnIdsAreTranslatedToWhatTheClientWasTold(@TempDir Path dir) {
        CrossBackendPalette palette = paletteKnowing(dir.resolve("palettes.nbt"), "skygen", skygenItems());
        List<ItemDefinition> clientItems = palette.buildClientItems("hub", hubItems());
        int clientSwordId = idOf(clientItems, "skygen:galaxy_sword");

        ItemPaletteMapping mapping = palette.mappingFor("skygen", skygenItems());

        assertNotNull(mapping);
        // Clientbound: skygen calls it 1301, the client knows that id as hub:menu — so it must be
        // renumbered on the way out or the player sees the wrong item entirely.
        assertEquals(clientSwordId, mapping.backendSide().getDefinition(VANILLA_MAX + 1).getRuntimeId());
        // Serverbound: the client's id must become skygen's again.
        assertEquals(VANILLA_MAX + 1, mapping.clientSide().getDefinition(clientSwordId).getRuntimeId());
        // Vanilla ids agree on both backends and must pass through untouched.
        assertEquals(1, mapping.backendSide().getDefinition(1).getRuntimeId());
    }

    /**
     * The mapping is only useful if it survives an actual encode: serializers write
     * {@code definition.getRuntimeId()} of the decoded object, which is the entire mechanism.
     */
    @Test
    void anItemDecodedFromSkygenEncodesToTheClientWithTheClientsId(@TempDir Path dir) {
        CrossBackendPalette palette = paletteKnowing(dir.resolve("palettes.nbt"), "skygen", skygenItems());
        List<ItemDefinition> clientItems = palette.buildClientItems("hub", hubItems());
        int clientSwordId = idOf(clientItems, "skygen:galaxy_sword");
        ItemPaletteMapping mapping = palette.mappingFor("skygen", skygenItems());

        BedrockCodecHelper backendHelper = helperWith(mapping.backendSide());
        BedrockCodecHelper clientHelper = helperWith(mapping.clientSide());

        ByteBuf fromBackend = Unpooled.buffer();
        backendHelper.writeItemInstance(fromBackend, ItemData.builder()
                .definition(item("skygen:galaxy_sword", VANILLA_MAX + 1))
                .count(1)
                .build());

        ItemData decoded = backendHelper.readItemInstance(fromBackend);
        ByteBuf toClient = Unpooled.buffer();
        clientHelper.writeItemInstance(toClient, decoded);

        // The id on the wire is what the client reads against the registry it was given at login, so
        // assert the bytes rather than decoding them back through the reverse map.
        assertEquals(clientSwordId, org.cloudburstmc.protocol.common.util.VarInts.readInt(toClient));
    }

    /**
     * Recipes name their ingredients rather than numbering them, and 1.26.40 sends roughly 900KB of
     * them at every join. A registry that only answers id lookups throws
     * {@code UnsupportedOperationException} from the interface default, the whole CraftingData packet
     * fails to decode, and every recipe on the server is lost.
     */
    @Test
    void recipesNameTheirIngredientsAndMustStillDecode(@TempDir Path dir) {
        CrossBackendPalette palette = paletteKnowing(dir.resolve("palettes.nbt"), "skygen", skygenItems());
        palette.buildClientItems("hub", hubItems());
        ItemPaletteMapping mapping = palette.mappingFor("skygen", skygenItems());

        BedrockCodecHelper backendHelper = helperWith(mapping.backendSide());
        CraftingDataPacket recipes = new CraftingDataPacket();
        // 1.26.40 splits recipes into typed lists; shaped is the one that reads ingredients by name.
        recipes.getShapedData().add(ShapedRecipeData.shaped(
                "skygen:sword_recipe", 1, 1,
                List.of(new ItemDescriptorWithCount(
                        new DefaultDescriptor(item("skygen:galaxy_sword", VANILLA_MAX + 1), 0), 1)),
                List.of(ItemData.builder().definition(item("skygen:galaxy_sword", VANILLA_MAX + 1)).count(1).build()),
                java.util.UUID.randomUUID(), "crafting_table", 0, 0, false,
                new RecipeUnlockingRequirement(RecipeUnlockingRequirement.UnlockingContext.NONE)
        ));

        ByteBuf encoded = Unpooled.buffer();
        Bedrock_v2168.CODEC.tryEncode(backendHelper, encoded, recipes);
        BedrockPacket decoded = Bedrock_v2168.CODEC.tryDecode(
                backendHelper, encoded, Bedrock_v2168.CODEC.getPacketDefinition(CraftingDataPacket.class).getId());

        assertTrue(decoded instanceof CraftingDataPacket);
        List<ShapedRecipeData> shaped = ((CraftingDataPacket) decoded).getShapedData();
        assertEquals(1, shaped.size());
        assertEquals("skygen:galaxy_sword", ((DefaultDescriptor) shaped.get(0).getIngredients().get(0).getDescriptor())
                .getItemId().getIdentifier());
    }

    @Test
    void anItemTheClientNeverLearnedPassesThroughRatherThanBecomingAir(@TempDir Path dir) {
        CrossBackendPalette palette = new CrossBackendPalette(BackendPaletteStore.load(dir.resolve("palettes.nbt")));
        palette.buildClientItems("hub", hubItems());

        ItemPaletteMapping mapping = palette.mappingFor("skygen", skygenItems());

        assertEquals(List.of("skygen:galaxy_sword", "skygen:10k"), mapping.unmappedFromBackend());
        // Wrong texture beats a slot the two ends disagree about.
        assertEquals(VANILLA_MAX + 2, mapping.backendSide().getDefinition(VANILLA_MAX + 2).getRuntimeId());
    }

    @Test
    void oneBackendAloneNeedsNoTranslation(@TempDir Path dir) {
        CrossBackendPalette palette = new CrossBackendPalette(BackendPaletteStore.load(dir.resolve("palettes.nbt")));
        palette.buildClientItems("hub", hubItems());

        assertTrue(palette.mappingFor("hub", hubItems()).isIdentity());
    }

    @Test
    void aClientWithNoRegistryYetHasNoMapping(@TempDir Path dir) {
        CrossBackendPalette palette = new CrossBackendPalette(BackendPaletteStore.load(dir.resolve("palettes.nbt")));

        assertNull(palette.mappingFor("hub", hubItems()));
    }

    @Test
    void entityListsAreMergedSoNobodyGoesInvisibleAfterASwitch(@TempDir Path dir) {
        NbtMap skygenEntities = entityList(List.of("skygen:generator", "minecraft:cow"));
        BackendPaletteStore store = BackendPaletteStore.load(dir.resolve("palettes.nbt"));
        store.learnEntityIdentifiers("skygen", skygenEntities);
        CrossBackendPalette palette = new CrossBackendPalette(store);

        NbtMap merged = palette.buildClientEntityIdentifiers("hub", entityList(List.of("hub:npc", "minecraft:cow")));

        List<String> ids = EntityPalettes.idList(merged).stream().map(EntityPalettes::entityId).toList();
        assertEquals(List.of("hub:npc", "minecraft:cow", "skygen:generator"), ids);
        // Duplicated rids would make the client resolve the wrong type for a spawn.
        assertEquals(3, EntityPalettes.idList(merged).stream()
                .map(entry -> entry.getInt("rid"))
                .distinct()
                .count());
    }

    @Test
    void customBlockDefinitionsAreMergedSoTheyRenderAfterASwitch(@TempDir Path dir) {
        BackendPaletteStore store = BackendPaletteStore.load(dir.resolve("palettes.nbt"));
        store.learnBlockProperties("skygen", List.of(
                new BlockPropertyData("skygen:generator", NbtMap.builder().putInt("id", 7).build()),
                new BlockPropertyData("skygen:crate", NbtMap.EMPTY)
        ));
        CrossBackendPalette palette = new CrossBackendPalette(store);

        List<BlockPropertyData> merged = palette.buildClientBlockProperties(
                "hub", List.of(new BlockPropertyData("hub:podium", NbtMap.EMPTY)));

        assertEquals(
                List.of("hub:podium", "skygen:generator", "skygen:crate"),
                merged.stream().map(BlockPropertyData::getName).toList()
        );
        // The definition has to survive the round trip, not just the name: it is what the client
        // renders from.
        assertEquals(7, merged.get(1).getProperties().getInt("id"));
    }

    /**
     * A client verifies its block palette against StartGame's checksum and disconnects with
     * {@code BlockMismatch} when they differ — before any chunk renders, with no message. Handing it
     * a deliberately larger palette than the backend described guarantees that mismatch, so the
     * checksum has to go with it.
     */
    @Test
    void addingForeignBlocksClearsTheChecksumTheClientWouldFailOn(@TempDir Path dir) {
        BackendPaletteStore store = BackendPaletteStore.load(dir.resolve("palettes.nbt"));
        store.learnBlockProperties("skygen", List.of(new BlockPropertyData("skygen:generator", NbtMap.EMPTY)));
        CrossBackendPalette palette = new CrossBackendPalette(store);

        StartGamePacket startGame = new StartGamePacket();
        startGame.setBlockNetworkIdsHashed(true);
        startGame.setBlockRegistryChecksum(-6083918988771959701L);

        assertTrue(palette.applyToStartGame("hub", startGame));
        assertEquals(1, startGame.getBlockProperties().size());
        assertEquals(0L, startGame.getBlockRegistryChecksum());
    }

    @Test
    void aBackendWithNothingToAddKeepsItsChecksum(@TempDir Path dir) {
        CrossBackendPalette palette = new CrossBackendPalette(BackendPaletteStore.load(dir.resolve("p.nbt")));
        StartGamePacket startGame = new StartGamePacket();
        startGame.setBlockNetworkIdsHashed(true);
        startGame.setBlockRegistryChecksum(42L);
        startGame.getBlockProperties().add(new BlockPropertyData("hub:podium", NbtMap.EMPTY));

        // The checksum is a real check on an ordinary join and would catch a corrupt palette;
        // clearing it unconditionally would hide that.
        assertTrue(!palette.applyToStartGame("hub", startGame));
        assertEquals(42L, startGame.getBlockRegistryChecksum());
    }

    /**
     * Where block ids are palette indices, a block's number <em>is</em> its position in the list
     * StartGame carries. Appending another backend's definitions therefore renumbers the world:
     * ordinary blocks resolve to the wrong entry and anything past the backend's own count draws as
     * the unknown block. Zeroing the checksum on top of that removes the client's own mismatch
     * check, so instead of a clean disconnect the player walks around a corrupted world.
     */
    @Test
    void aBackendThatDoesNotHashBlockIdsKeepsItsPaletteExactlyAsSent(@TempDir Path dir) {
        CrossBackendPalette palette = new CrossBackendPalette(BackendPaletteStore.load(dir.resolve("p.nbt")));

        StartGamePacket hub = new StartGamePacket();
        hub.setBlockNetworkIdsHashed(true);
        hub.getBlockProperties().add(new BlockPropertyData("hub:podium", NbtMap.EMPTY));
        palette.applyToStartGame("hub", hub);

        StartGamePacket geyser = new StartGamePacket();
        geyser.setBlockNetworkIdsHashed(false);
        geyser.setBlockRegistryChecksum(1234L);
        geyser.getBlockProperties().add(new BlockPropertyData("geyser_custom:test_block", NbtMap.EMPTY));

        assertFalse(palette.applyToStartGame("javatest", geyser),
                "a palette-indexed backend's StartGame must not be rewritten");
        assertEquals(1, geyser.getBlockProperties().size(),
                "hub's block must not be appended - it would shift every index the client resolves");
        assertEquals("geyser_custom:test_block", geyser.getBlockProperties().get(0).getName());
        assertEquals(1234L, geyser.getBlockRegistryChecksum(),
                "the checksum is the client's own mismatch check and must survive");
    }

    /**
     * The other direction: an index means nothing anywhere else, so a palette-indexed backend's
     * blocks must not be pushed into a hashed backend's registry either.
     */
    @Test
    void blocksFromAPaletteIndexedBackendAreNotSharedWithOthers(@TempDir Path dir) {
        CrossBackendPalette palette = new CrossBackendPalette(BackendPaletteStore.load(dir.resolve("p.nbt")));

        StartGamePacket geyser = new StartGamePacket();
        geyser.setBlockNetworkIdsHashed(false);
        geyser.getBlockProperties().add(new BlockPropertyData("geyser_custom:test_block", NbtMap.EMPTY));
        palette.applyToStartGame("javatest", geyser);

        StartGamePacket hub = new StartGamePacket();
        hub.setBlockNetworkIdsHashed(true);
        hub.setBlockRegistryChecksum(99L);
        hub.getBlockProperties().add(new BlockPropertyData("hub:podium", NbtMap.EMPTY));

        assertFalse(palette.applyToStartGame("hub", hub),
                "nothing should have been learned from the palette-indexed backend");
        assertEquals(1, hub.getBlockProperties().size());
        assertEquals(99L, hub.getBlockRegistryChecksum());
    }

    @Test
    void aBackendThatDoesNotHashBlockIdsIsReportedRatherThanSilentlyWrong(@TempDir Path dir) {
        CrossBackendPalette palette = new CrossBackendPalette(BackendPaletteStore.load(dir.resolve("p.nbt")));

        // Nothing to assert beyond it being reported once and not throwing: hashed ids are what make
        // sharing blocks possible at all, and the proxy cannot fix a backend that numbers them.
        palette.warnIfBlockIdsNotHashed("skygen", false);
        palette.warnIfBlockIdsNotHashed("skygen", true);
    }

    @Test
    void whatOneBackendTeachesIsRememberedForTheNextRestart(@TempDir Path dir) {
        Path cacheFile = dir.resolve("cache").resolve("palettes.nbt");
        BackendPaletteStore store = BackendPaletteStore.load(cacheFile);
        store.learnItems("skygen", skygenItems());
        store.learnEntityIdentifiers("skygen", entityList(List.of("skygen:generator")));
        store.learnEntityProperty("skygen", NbtMap.builder().putString("type", "skygen:generator").build());
        store.learnBlockProperties("skygen", List.of(
                new BlockPropertyData("skygen:generator", NbtMap.builder().putInt("id", 7).build())));
        // Writes are deferred and coalesced; this is the shutdown flush.
        store.flush();

        BackendPaletteStore reloaded = BackendPaletteStore.load(cacheFile);

        BackendPalette skygen = reloaded.palette("skygen");
        assertEquals(1, skygen.blockProperties().size());
        assertEquals("skygen:generator", skygen.blockProperties().get(0).getName());
        assertEquals(7, skygen.blockProperties().get(0).getProperties().getInt("id"));
        assertNotNull(skygen);
        assertEquals(skygenItems().size(), skygen.items().size());
        assertEquals(VANILLA_MAX + 1, skygen.items().get(2).getRuntimeId());
        assertEquals("skygen:galaxy_sword", skygen.items().get(2).getIdentifier());
        assertEquals(1, EntityPalettes.idList(skygen.entityIdentifiers()).size());
        assertEquals(1, skygen.entityProperties().size());
    }

    /**
     * Learning must cost nothing on disk while it happens.
     *
     * <p>Every mutation used to rewrite, GZIP and replace the whole cache synchronously — 79 times
     * during one join against a backend with 2353 items, ~195 ms each, on the packet thread. The
     * 15 s that added overran the client's join timeout, so a large backend could not be joined
     * through the proxy at all.</p>
     */
    @Test
    void aBurstOfDefinitionsCostsNoWritesUntilItIsFlushed(@TempDir Path dir) throws Exception {
        Path cacheFile = dir.resolve("cache").resolve("palettes.nbt");
        BackendPaletteStore store = BackendPaletteStore.load(cacheFile);

        store.learnItems("skygen", skygenItems());
        for (int i = 0; i < 79; i++) {
            // Still reports what actually changed: the caller suppresses the packet when it did not.
            assertTrue(store.learnEntityProperty(
                    "skygen", NbtMap.builder().putString("type", "skygen:entity" + i).build()));
        }

        assertFalse(Files.exists(cacheFile), "learning must not write the cache on the packet thread");

        store.flush();
        assertTrue(Files.exists(cacheFile));
        assertEquals(79, BackendPaletteStore.load(cacheFile).palette("skygen").entityProperties().size());

        // Nothing has been learned since, so there is nothing to write: a flush of a clean store must
        // not touch the file at all.
        Files.delete(cacheFile);
        store.flush();
        assertFalse(Files.exists(cacheFile), "a clean store must not rewrite the cache");
    }

    @Test
    void aDisabledStoreLearnsNothing(@TempDir Path dir) {
        BackendPaletteStore store = BackendPaletteStore.disabled();

        assertTrue(!store.isEnabled());
        assertTrue(!store.learnItems("skygen", skygenItems()));
        assertTrue(store.knownBackends().isEmpty());
    }

    private static BedrockCodecHelper helperWith(
            org.cloudburstmc.protocol.common.DefinitionRegistry<ItemDefinition> itemDefinitions
    ) {
        BedrockCodecHelper helper = Bedrock_v2168.CODEC.createHelper();
        helper.setItemDefinitions(itemDefinitions);
        helper.setBlockDefinitions(org.endstone.proxy.codec.CodecDefinitionState.unknownBlockDefinitions());
        return helper;
    }

    private static int idOf(List<ItemDefinition> items, String identifier) {
        return items.stream()
                .filter(item -> item.getIdentifier().equals(identifier))
                .findFirst()
                .orElseThrow(() -> new AssertionError(identifier + " missing from " + items))
                .getRuntimeId();
    }

    private static NbtMap entityList(List<String> identifiers) {
        List<NbtMap> entries = new java.util.ArrayList<>();
        int runtimeId = 1;
        for (String identifier : identifiers) {
            entries.add(NbtMap.builder()
                    .putString("id", identifier)
                    .putInt("rid", runtimeId++)
                    .putString("bid", "")
                    .putBoolean("hasspawnegg", false)
                    .putBoolean("summonable", true)
                    .build());
        }
        return NbtMap.builder().putList("idlist", NbtType.COMPOUND, entries).build();
    }
}
