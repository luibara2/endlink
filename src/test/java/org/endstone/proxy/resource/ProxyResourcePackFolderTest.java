package org.endstone.proxy.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProxyResourcePackFolderTest {
    private static final UUID PACK_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static String manifest(UUID uuid, String name, String version) {
        return """
                {
                  "format_version": 2,
                  "header": {
                    "name": "%s",
                    "uuid": "%s",
                    "version": %s,
                    "min_engine_version": [1, 21, 0]
                  },
                  "modules": [
                    { "type": "resources", "uuid": "66666666-7777-8888-9999-000000000000", "version": %s }
                  ]
                }
                """.formatted(name, uuid, version, version);
    }

    private static Path writePackFolder(Path dir, String folderName, UUID uuid, String name) throws Exception {
        Path pack = Files.createDirectories(dir.resolve(folderName));
        Files.writeString(pack.resolve("manifest.json"), manifest(uuid, name, "[2, 1, 0]"), StandardCharsets.UTF_8);
        Files.writeString(pack.resolve("pack_icon.png"), "not really a png", StandardCharsets.UTF_8);
        Files.createDirectories(pack.resolve("textures/blocks"));
        Files.writeString(pack.resolve("textures/blocks/stone.png"), "texture bytes", StandardCharsets.UTF_8);
        return pack;
    }

    private static List<String> zipEntryNames(byte[] data) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    @Test
    void aPackFolderIsServedAsAZipTheClientCanRead(@TempDir Path dir) throws Exception {
        writePackFolder(dir, "hub_rp", PACK_UUID, "Hub Pack");

        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.load(dir);

        assertEquals(1, registry.packs().size());
        ProxyResourcePackEntry pack = registry.packs().get(0);
        assertEquals(PACK_UUID, pack.uuid());
        assertEquals("Hub Pack", pack.name());
        assertEquals("2.1.0", pack.versionString());

        List<String> names = zipEntryNames(pack.data());
        assertTrue(names.contains("manifest.json"), () -> "manifest must sit at the zip root, got " + names);
        assertTrue(names.contains("textures/blocks/stone.png"), () -> "nested files must keep their path, got " + names);
    }

    @Test
    void aFolderZipIsReproducibleSoClientsDoNotRedownloadAfterARestart(@TempDir Path dir) throws Exception {
        writePackFolder(dir, "hub_rp", PACK_UUID, "Hub Pack");

        byte[] first = ProxyResourcePackRegistry.load(dir).packs().get(0).hash();
        byte[] second = ProxyResourcePackRegistry.load(dir).packs().get(0).hash();

        assertArrayEquals(first, second);
    }

    @Test
    void aPackUnpackedOneLevelDownIsRootedAtItsManifest(@TempDir Path dir) throws Exception {
        Path wrapper = Files.createDirectories(dir.resolve("casino"));
        writePackFolder(wrapper, "casino_rp", PACK_UUID, "Casino");

        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.load(dir);

        assertEquals(1, registry.packs().size());
        assertTrue(zipEntryNames(registry.packs().get(0).data()).contains("manifest.json"));
    }

    @Test
    void foldersAndMcpackFilesLoadSideBySide(@TempDir Path dir) throws Exception {
        writePackFolder(dir, "hub_rp", PACK_UUID, "Hub Pack");
        Path staging = writePackFolder(dir.resolve("staging"), "zipped", UUID.randomUUID(), "Zipped Pack");
        Path mcpack = dir.resolve("zipped.mcpack");
        zipInto(staging, mcpack);
        deleteRecursively(dir.resolve("staging"));

        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.load(dir);

        assertEquals(2, registry.packs().size());
        assertTrue(registry.isProxyPack(PACK_UUID));
    }

    @Test
    void aDirectoryWithoutAManifestIsIgnored(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("notes/subfolder"));
        Files.writeString(dir.resolve("notes/readme.txt"), "nothing to see", StandardCharsets.UTF_8);

        assertTrue(ProxyResourcePackRegistry.load(dir).isEmpty());
    }

    private static void zipInto(Path root, Path target) throws Exception {
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(target));
             var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(root.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
