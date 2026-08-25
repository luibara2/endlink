package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.endstone.proxy.resource.BackendPackCache;
import org.endstone.proxy.resource.ProxyResourcePackEntry;
import org.endstone.proxy.resource.ProxyResourcePackRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BackendPackFetchTest {
    private static final UUID PACK_ID = UUID.fromString("37ff0d1f-f6cd-45ab-bdd2-9f155d965837");

    @Test
    void cachedPackIsNotDownloadedAgainWhenBackendAdvertisesItsContentSize(@TempDir Path dir) throws Exception {
        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.mutableEmpty();
        BackendPackCache cache = BackendPackCache.of(dir, registry);
        byte[] archive = pack();
        assertTrue(cache.store(PACK_ID, archive, null));
        ProxyResourcePackEntry cached = registry.findByUuid(PACK_ID);
        assertNotNull(cached);
        assertNotEquals(archive.length, cached.contentSize(),
                "the regression requires different compressed and expanded sizes");

        List<BedrockPacket> sent = new ArrayList<>();
        BackendPackFetch fetch = BackendPackFetch.start(
                cache,
                "arena",
                info(cached.contentSize()),
                sent::add,
                () -> {
                }
        );

        assertNull(fetch, "a current cached pack must let the backend handshake continue immediately");
        assertTrue(sent.isEmpty(), "the proxy must not request bytes it already cached");
    }

    @Test
    void changedContentSizeStillRequestsTheBackendPack(@TempDir Path dir) throws Exception {
        ProxyResourcePackRegistry registry = ProxyResourcePackRegistry.mutableEmpty();
        BackendPackCache cache = BackendPackCache.of(dir, registry);
        byte[] archive = pack();
        assertTrue(cache.store(PACK_ID, archive, null));
        ProxyResourcePackEntry cached = registry.findByUuid(PACK_ID);

        List<BedrockPacket> sent = new ArrayList<>();
        BackendPackFetch fetch = BackendPackFetch.start(
                cache,
                "arena",
                info(cached.contentSize() + 1),
                sent::add,
                () -> {
                }
        );

        assertNotNull(fetch);
        ResourcePackClientResponsePacket request = assertInstanceOf(
                ResourcePackClientResponsePacket.class, sent.get(0));
        assertEquals(ResourcePackClientResponsePacket.Status.SEND_PACKS, request.getStatus());
        assertEquals(List.of(PACK_ID + "_4.0.0"), request.getPackIds());
    }

    private static ResourcePacksInfoPacket info(long contentSize) {
        ResourcePacksInfoPacket info = new ResourcePacksInfoPacket();
        info.getResourcePackInfos().add(new ResourcePacksInfoPacket.Entry(
                PACK_ID, "4.0.0", contentSize, "", "", "", false, false, false, ""));
        return info;
    }

    private static byte[] pack() throws Exception {
        String manifest = """
                {"format_version":2,"header":{"name":"Arena","uuid":"%s","version":[4,0,0]}}
                """.formatted(PACK_ID);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("textures/padding.bin"));
            zip.write(new byte[32 * 1024]);
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
