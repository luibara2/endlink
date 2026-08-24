package org.endstone.proxy.resource;

import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a client is told about resource packs, when the proxy holds copies of some of them.
 *
 * <p>These exist because of a failure that looked nothing like a pack problem: items with no texture
 * at all, in the inventory, the creative menu, the recipe book and on the ground, while every block
 * rendered correctly - and only through the proxy, never on a direct connection. The client said what
 * was wrong if you looked at its content log, {@code ... requires either an icon atlas or icon
 * texture}: it was applying a resource pack whose item atlas did not cover the items the backend
 * actually had, because the proxy was serving a copy of that pack from before it was edited.</p>
 */
final class ProxyResourcePackMergeTest {
    private static final UUID MAIN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CROWN = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID FOREIGN = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static byte[] pack(UUID uuid, String name, String version, int padding) throws Exception {
        String manifest = """
                {
                  "format_version": 2,
                  "header": { "name": "%s", "uuid": "%s", "version": %s },
                  "modules": [ { "type": "resources", "uuid": "%s", "version": %s } ]
                }
                """.formatted(name, uuid, version, UUID.randomUUID(), version);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (padding > 0) {
                zip.putNextEntry(new ZipEntry("textures/item_texture.json"));
                zip.write(new byte[padding]);
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static ResourcePacksInfoPacket.Entry backendEntry(UUID uuid, String version, long size) {
        return new ResourcePacksInfoPacket.Entry(
                uuid, version, size, "", "sub", "content-" + uuid, false, true, true, "");
    }

    private static ResourcePacksInfoPacket info(ResourcePacksInfoPacket.Entry... entries) {
        ResourcePacksInfoPacket packet = new ResourcePacksInfoPacket();
        packet.getResourcePackInfos().addAll(List.of(entries));
        return packet;
    }

    private static List<UUID> ids(ResourcePacksInfoPacket packet) {
        List<UUID> ids = new ArrayList<>();
        for (ResourcePacksInfoPacket.Entry entry : packet.getResourcePackInfos()) {
            ids.add(entry.getPackId());
        }
        return ids;
    }

    private static ProxyResourcePackRegistry registryOf(ProxyResourcePackEntry... entries) {
        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.mutableEmpty();
        for (ProxyResourcePackEntry entry : entries) {
            registry.add(entry);
        }
        return registry;
    }

    @Test
    void aCachedCopyThatNoLongerMatchesTheBackendIsLeftToTheBackend() throws Exception {
        // Same uuid, same version, different bytes: the pack was edited in place, which is the normal
        // way a server's pack changes. A version comparison cannot see it; the size can.
        ProxyResourcePackEntry cached = ProxyResourcePackRegistry.entryFrom(pack(MAIN, "Main", "[4, 0, 0]", 64));
        ProxyResourcePackRegistry registry = registryOf(cached);

        ProxyResourcePackRegistry.MergedPacksInfo merged = registry.buildMergedInfo(
                info(backendEntry(MAIN, "4.0.0", cached.data().length + 4096)));

        assertFalse(merged.servedByProxy().contains(MAIN),
                "the proxy must not serve a copy it knows is not the backend's");
        assertEquals(1, merged.stale().size());
        assertEquals(cached.data().length + 4096,
                merged.packet().getResourcePackInfos().get(0).getPackSize(),
                "the client is pointed at the backend's copy, described as the backend described it");
    }

    @Test
    void aCachedCopyThatMatchesIsServedByTheProxy() throws Exception {
        ProxyResourcePackEntry cached = ProxyResourcePackRegistry.entryFrom(pack(MAIN, "Main", "[4, 0, 0]", 64));
        ProxyResourcePackRegistry registry = registryOf(cached);

        ProxyResourcePackRegistry.MergedPacksInfo merged = registry.buildMergedInfo(
                info(backendEntry(MAIN, "4.0.0", cached.data().length)));

        assertTrue(merged.servedByProxy().contains(MAIN));
        assertTrue(merged.stale().isEmpty());
    }

    @Test
    void theProxysCopyIsDescribedTheWayTheBackendDescribedIt() throws Exception {
        ProxyResourcePackEntry cached = ProxyResourcePackRegistry.entryFrom(pack(MAIN, "Main", "[4, 0, 0]", 64));
        ProxyResourcePackRegistry registry = registryOf(cached);

        ResourcePacksInfoPacket.Entry served = registry.buildMergedInfo(
                        info(backendEntry(MAIN, "4.0.0", cached.data().length)))
                .packet().getResourcePackInfos().get(0);

        // Every one of these changes how the client treats the pack. Inventing values for them is how
        // a pack that loads on a direct connection loads differently, or not at all, through a proxy.
        assertEquals("sub", served.getSubPackName());
        assertEquals("content-" + MAIN, served.getContentId());
        assertTrue(served.isAddonPack());
        assertTrue(served.isRaytracingCapable());
        // Except the two the proxy genuinely owns: it is the source, and it knows its own size.
        assertEquals("", served.getCdnUrl());
        assertEquals(cached.data().length, served.getPackSize());
    }

    @Test
    void anEncryptedPackIsAlwaysLeftToTheBackend() throws Exception {
        ProxyResourcePackEntry cached = ProxyResourcePackRegistry.entryFrom(pack(MAIN, "Main", "[4, 0, 0]", 64));
        ProxyResourcePackRegistry registry = registryOf(cached);
        ResourcePacksInfoPacket.Entry encrypted = new ResourcePacksInfoPacket.Entry(
                MAIN, "4.0.0", cached.data().length, "a-content-key", "", "", false, false, false, "");

        ProxyResourcePackRegistry.MergedPacksInfo merged = registry.buildMergedInfo(info(encrypted));

        assertFalse(merged.servedByProxy().contains(MAIN),
                "only the backend can hand out the key, so only the backend can hand out the pack");
    }

    @Test
    void theBackendsOrderIsKeptAndForeignPacksGoLast() throws Exception {
        // The stack is a precedence order: where two packs carry the same file, their order decides
        // which one the client uses. Reordering it renders the backend's own packs differently from a
        // direct connection, which is exactly the bug this whole test class is about.
        ProxyResourcePackEntry crown = ProxyResourcePackRegistry.entryFrom(pack(CROWN, "Crown", "[4, 0, 0]", 8));
        ProxyResourcePackEntry main = ProxyResourcePackRegistry.entryFrom(pack(MAIN, "Main", "[4, 0, 0]", 64));
        ProxyResourcePackEntry foreign = ProxyResourcePackRegistry.entryFrom(pack(FOREIGN, "Afk", "[1, 0, 0]", 4));
        // Deliberately not in the backend's order, the way a cache directory listing would give them.
        ProxyResourcePackRegistry registry = registryOf(crown, foreign, main);

        ProxyResourcePackRegistry.MergedPacksInfo merged = registry.buildMergedInfo(info(
                backendEntry(MAIN, "4.0.0", main.data().length),
                backendEntry(CROWN, "4.0.0", crown.data().length)));

        assertEquals(List.of(MAIN, CROWN, FOREIGN), ids(merged.packet()));
        assertEquals(3, merged.servedByProxy().size());
    }

    @Test
    void theStackKeepsTheBackendsOrderToo() throws Exception {
        ProxyResourcePackEntry crown = ProxyResourcePackRegistry.entryFrom(pack(CROWN, "Crown", "[4, 0, 0]", 8));
        ProxyResourcePackEntry main = ProxyResourcePackRegistry.entryFrom(pack(MAIN, "Main", "[4, 0, 0]", 64));
        ProxyResourcePackEntry foreign = ProxyResourcePackRegistry.entryFrom(pack(FOREIGN, "Afk", "[1, 0, 0]", 4));
        ProxyResourcePackRegistry registry = registryOf(crown, foreign, main);

        ResourcePackStackPacket backendStack = new ResourcePackStackPacket();
        backendStack.getResourcePacks().add(new ResourcePackStackPacket.Entry(MAIN.toString(), "4.0.0", ""));
        backendStack.getResourcePacks().add(new ResourcePackStackPacket.Entry(CROWN.toString(), "4.0.0", ""));

        ResourcePackStackPacket merged = registry.buildMergedStack(backendStack);

        assertEquals(
                List.of(MAIN.toString(), CROWN.toString(), FOREIGN.toString()),
                merged.getResourcePacks().stream().map(ResourcePackStackPacket.Entry::getPackId).toList());
    }

    @Test
    void aBackendPackTheProxyHasNeverSeenIsPassedStraightThrough() throws Exception {
        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.mutableEmpty();
        ResourcePacksInfoPacket.Entry unknown = backendEntry(MAIN, "4.0.0", 1234);

        ProxyResourcePackRegistry.MergedPacksInfo merged = registry.buildMergedInfo(info(unknown));

        assertEquals(List.of(MAIN), ids(merged.packet()));
        assertTrue(merged.servedByProxy().isEmpty());
    }

    @Test
    void theBackendsVibrantVisualsChoiceSurvivesTheMerge() {
        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.mutableEmpty();
        ResourcePacksInfoPacket backendInfo = new ResourcePacksInfoPacket();
        backendInfo.setVibrantVisualsForceDisabled(true);
        backendInfo.setForcedToAccept(true);

        ProxyResourcePackRegistry.MergedPacksInfo merged = registry.buildMergedInfo(backendInfo);

        assertTrue(merged.packet().isVibrantVisualsForceDisabled(),
                "a backend turning vibrant visuals off is saying how its packs are meant to be rendered");
        assertTrue(merged.packet().isForcedToAccept());
    }
}
