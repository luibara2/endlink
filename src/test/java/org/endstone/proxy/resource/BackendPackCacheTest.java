package org.endstone.proxy.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A backend's packs can only be served to a switching player if the proxy already has them, and the
 * proxy only ever sees them while somebody downloads them. These pin what happens to those bytes.
 */
final class BackendPackCacheTest {
    private static final UUID PACK_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private static byte[] pack(UUID uuid, String name, String version) throws Exception {
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
        }
        return buffer.toByteArray();
    }

    /** The same pack with more in it: an edit that keeps the manifest version, as a real one does. */
    private static byte[] paddedPack(UUID uuid, String name, String version, int extraBytes) throws Exception {
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
            zip.putNextEntry(new ZipEntry("textures/item_texture.json"));
            zip.write(new byte[extraBytes]);
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    @Test
    void aDownloadedPackIsServedToEveryoneAndSurvivesARestart(@TempDir Path dir) throws Exception {
        Path cacheDir = dir.resolve("cache/packs");
        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.load(dir.resolve("res"), cacheDir);
        BackendPackCache cache = BackendPackCache.of(cacheDir, registry);
        byte[] data = pack(PACK_UUID, "Skygen", "[4, 0, 0]");

        assertTrue(cache.store(PACK_UUID, data, sha256(data)));

        // Served immediately: the next player to log in gets it without waiting for a restart.
        assertTrue(registry.isProxyPack(PACK_UUID));
        assertEquals("4.0.0", registry.findByUuid(PACK_UUID).versionString());
        // And it is on disk under a name that says what it is.
        assertTrue(Files.exists(cacheDir.resolve(PACK_UUID + "_4.0.0.mcpack")));

        ProxyResourcePackRegistry reloaded = ProxyResourcePackRegistry.load(dir.resolve("res"), cacheDir);
        assertTrue(reloaded.isProxyPack(PACK_UUID));
    }

    @Test
    void aPackThatDoesNotMatchItsAdvertisedHashIsRejected(@TempDir Path dir) throws Exception {
        Path cacheDir = dir.resolve("packs");
        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.mutableEmpty();
        BackendPackCache cache = BackendPackCache.of(cacheDir, registry);
        byte[] data = pack(PACK_UUID, "Skygen", "[4, 0, 0]");

        // A truncated or altered download would otherwise be served to every future player as though
        // the backend had sent it.
        assertFalse(cache.store(PACK_UUID, data, sha256("something else".getBytes(StandardCharsets.UTF_8))));
        assertFalse(registry.isProxyPack(PACK_UUID));
    }

    @Test
    void aPackWhoseManifestClaimsAnotherUuidIsRejected(@TempDir Path dir) throws Exception {
        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.mutableEmpty();
        BackendPackCache cache = BackendPackCache.of(dir, registry);
        byte[] data = pack(UUID.randomUUID(), "Impostor", "[1, 0, 0]");

        assertFalse(cache.store(PACK_UUID, data, null));
        assertTrue(registry.packs().isEmpty());
    }

    @Test
    void theNewestVersionOfAPackWins(@TempDir Path dir) throws Exception {
        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.mutableEmpty();
        BackendPackCache cache = BackendPackCache.of(dir, registry);

        assertTrue(cache.store(PACK_UUID, pack(PACK_UUID, "Skygen", "[4, 0, 0]"), null));
        assertTrue(cache.store(PACK_UUID, pack(PACK_UUID, "Skygen", "[4, 1, 0]"), null));
        assertEquals("4.1.0", registry.findByUuid(PACK_UUID).versionString());

        // An older copy from another backend must not displace it.
        assertFalse(cache.store(PACK_UUID, pack(PACK_UUID, "Skygen", "[3, 0, 0]"), null));
        assertEquals("4.1.0", registry.findByUuid(PACK_UUID).versionString());
        assertEquals(1, registry.packs().size());
    }

    @Test
    void alreadyServedPacksAreNotDownloadedAgain(@TempDir Path dir) throws Exception {
        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.mutableEmpty();
        BackendPackCache cache = BackendPackCache.of(dir, registry);
        cache.store(PACK_UUID, pack(PACK_UUID, "Skygen", "[4, 0, 0]"), null);

        assertTrue(cache.has(PACK_UUID, new int[]{4, 0, 0}));
        assertTrue(cache.has(PACK_UUID, new int[]{3, 9, 9}));
        // A newer version on the backend is worth fetching.
        assertFalse(cache.has(PACK_UUID, new int[]{4, 0, 1}));
    }

    @Test
    void aPackEditedWithoutAVersionBumpIsRelearned(@TempDir Path dir) throws Exception {
        // The way a server's pack actually changes: edit the files, keep the manifest version. The
        // proxy used to compare versions alone, decide its copy was current, and go on serving the
        // previous edit to every player for the rest of its life - a pack whose item atlas no longer
        // covers the backend's items, which the client draws as no icon at all.
        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.mutableEmpty();
        BackendPackCache cache = BackendPackCache.of(dir, registry);
        byte[] before = pack(PACK_UUID, "Skygen", "[4, 0, 0]");
        byte[] after = paddedPack(PACK_UUID, "Skygen", "[4, 0, 0]", 512);

        assertTrue(cache.store(PACK_UUID, before, sha256(before)));
        assertTrue(cache.store(PACK_UUID, after, sha256(after)));

        assertEquals(after.length, registry.findByUuid(PACK_UUID).data().length);
        assertEquals(1, registry.packs().size());
        // Re-storing the same bytes is not a change, so it does not churn the file or the pack order.
        assertFalse(cache.store(PACK_UUID, after, sha256(after)));
    }

    @Test
    void anEditedPackIsNoLongerConsideredCurrent(@TempDir Path dir) throws Exception {
        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.mutableEmpty();
        BackendPackCache cache = BackendPackCache.of(dir, registry);
        byte[] cached = pack(PACK_UUID, "Skygen", "[4, 0, 0]");
        cache.store(PACK_UUID, cached, sha256(cached));
        ProxyResourcePackEntry cachedEntry = registry.findByUuid(PACK_UUID);
        int[] sameVersion = {4, 0, 0};

        assertNotEquals(cached.length, cachedEntry.contentSize(),
                "the protocol's content size and the downloaded archive size are different fields");
        assertTrue(cache.hasCurrentContent(PACK_UUID, sameVersion, cachedEntry.contentSize(), sha256(cached)));
        // Either the size or the hash disagreeing is enough to know the copy is out of date.
        assertFalse(cache.hasCurrentContent(PACK_UUID, sameVersion, cachedEntry.contentSize() + 1, null));
        assertFalse(cache.hasCurrentContent(PACK_UUID, sameVersion, 0,
                sha256("a different edit".getBytes(StandardCharsets.UTF_8))));
        // A backend that says nothing about either leaves the cached copy alone, as before.
        assertTrue(cache.hasCurrentContent(PACK_UUID, sameVersion, 0, null));
        // And an older backend copy still does not displace a newer cached one.
        assertTrue(cache.hasCurrentContent(PACK_UUID, new int[]{3, 0, 0}, 999_999, null));
    }

    @Test
    void compressedDataInfoSizeIsCheckedAgainstTheArchiveRatherThanContent(@TempDir Path dir) throws Exception {
        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.mutableEmpty();
        BackendPackCache cache = BackendPackCache.of(dir, registry);
        byte[] cached = paddedPack(PACK_UUID, "Skygen", "[4, 0, 0]", 32 * 1024);
        cache.store(PACK_UUID, cached, sha256(cached));

        assertTrue(cache.hasCurrentCompressed(PACK_UUID, new int[]{4, 0, 0}, cached.length, null));
        assertFalse(cache.hasCurrentCompressed(PACK_UUID, new int[]{4, 0, 0}, cached.length + 1, null));
    }

    @Test
    void aHandPlacedPackIsNotReplacedByTheBackendsCopy(@TempDir Path dir) throws Exception {
        Path res = Files.createDirectories(dir.resolve("res"));
        Path cacheDir = Files.createDirectories(dir.resolve("cache/packs"));
        Files.write(res.resolve("mine.mcpack"), pack(PACK_UUID, "Hand placed", "[4, 0, 0]"));
        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.load(res, cacheDir);
        BackendPackCache cache = BackendPackCache.of(cacheDir, registry);

        // Overriding what the backend serves is the entire reason to put a pack in that directory.
        assertFalse(cache.store(PACK_UUID, paddedPack(PACK_UUID, "Backend copy", "[4, 0, 0]", 512), null));
        assertEquals("Hand placed", registry.findByUuid(PACK_UUID).name());
    }

    @Test
    void relearningAPackLeavesItWhereItWasInTheStack(@TempDir Path dir) throws Exception {
        // The registry's order becomes the client's pack stack order, which decides whose copy of a
        // shared file wins. A relearned pack that jumped to the end would silently change that.
        UUID other = UUID.fromString("11111111-2222-3333-4444-555555555555");
        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.mutableEmpty();
        BackendPackCache cache = BackendPackCache.of(dir, registry);
        cache.store(PACK_UUID, pack(PACK_UUID, "First", "[1, 0, 0]"), null);
        cache.store(other, pack(other, "Second", "[1, 0, 0]"), null);

        cache.store(PACK_UUID, paddedPack(PACK_UUID, "First", "[1, 0, 0]", 256), null);

        assertEquals(
                java.util.List.of(PACK_UUID, other),
                registry.packs().stream().map(ProxyResourcePackEntry::uuid).toList());
    }

    @Test
    void aHandPlacedPackWinsATieAgainstTheCachedCopy(@TempDir Path dir) throws Exception {
        Path res = Files.createDirectories(dir.resolve("res"));
        Path cacheDir = Files.createDirectories(dir.resolve("cache/packs"));
        Files.write(res.resolve("mine.mcpack"), pack(PACK_UUID, "Hand placed", "[4, 0, 0]"));
        Files.write(cacheDir.resolve(PACK_UUID + "_4.0.0.mcpack"), pack(PACK_UUID, "Cached copy", "[4, 0, 0]"));

        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.load(res, cacheDir);

        assertEquals(1, registry.packs().size());
        assertEquals("Hand placed", registry.findByUuid(PACK_UUID).name());
    }

    @Test
    void aDisabledCacheKeepsNothing(@TempDir Path dir) throws Exception {
        BackendPackCache cache = BackendPackCache.disabled();

        assertFalse(cache.isEnabled());
        assertFalse(cache.store(PACK_UUID, pack(PACK_UUID, "Skygen", "[1, 0, 0]"), null));
        assertFalse(cache.has(PACK_UUID, new int[]{1, 0, 0}));
    }

    @Test
    void addingToTheSharedEmptyRegistryIsRefused() throws Exception {
        // Every packless proxy holds this one instance; adding to it would serve one connection's
        // packs to every other.
        ProxyResourcePackEntry entry = ProxyResourcePackRegistry.entryFrom(pack(PACK_UUID, "Skygen", "[1, 0, 0]"));

        assertNotNull(entry);
        assertFalse(ProxyResourcePackRegistry.empty().add(entry));
        assertTrue(ProxyResourcePackRegistry.empty().packs().isEmpty());
    }
}
