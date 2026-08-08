package org.endstone.proxy.resource;

import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.data.ResourcePackType;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackChunkDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackDataInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.endstone.proxy.listener.ListenerSession;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ProxyResourcePackRegistry {
    private static final ProxyResourcePackRegistry EMPTY = new ProxyResourcePackRegistry(Collections.emptyList());

    private final List<ProxyResourcePackEntry> packs;
    private final Map<UUID, ProxyResourcePackEntry> packsByUuid;

    private ProxyResourcePackRegistry(List<ProxyResourcePackEntry> packs) {
        this.packs = Collections.unmodifiableList(new ArrayList<>(packs));
        Map<UUID, ProxyResourcePackEntry> map = new LinkedHashMap<>();
        for (ProxyResourcePackEntry pack : packs) {
            map.put(pack.uuid(), pack);
        }
        this.packsByUuid = Collections.unmodifiableMap(map);
    }

    public static ProxyResourcePackRegistry empty() {
        return EMPTY;
    }

    public static ProxyResourcePackRegistry load(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return EMPTY;
        }
        List<ProxyResourcePackEntry> loaded = new ArrayList<>();
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mcpack"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            ProxyResourcePackEntry entry = loadPack(p);
                            if (entry != null) {
                                loaded.add(entry);
                                System.out.printf(
                                        "Loaded proxy resource pack: %s v%s (uuid=%s, %d bytes).%n",
                                        entry.name(), entry.versionString(), entry.uuid(), entry.data().length
                                );
                            }
                        } catch (Exception e) {
                            System.out.printf("Failed to load proxy resource pack %s: %s%n", p.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            System.out.printf("Failed to list proxy resource packs directory %s: %s%n", dir, e.getMessage());
            return EMPTY;
        }
        if (loaded.isEmpty()) {
            return EMPTY;
        }
        return new ProxyResourcePackRegistry(loaded);
    }

    private static ProxyResourcePackEntry loadPack(Path path) throws Exception {
        byte[] data = Files.readAllBytes(path);
        ManifestInfo manifest = parseManifest(data);
        if (manifest == null) {
            System.out.printf("Skipping %s: no valid manifest.json found.%n", path.getFileName());
            return null;
        }
        byte[] hash = sha256(data);
        return new ProxyResourcePackEntry(manifest.uuid(), manifest.version(), manifest.name(), data, hash);
    }

    private static ManifestInfo parseManifest(byte[] zipData) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.equals("manifest.json") || name.endsWith("/manifest.json")) {
                    byte[] manifestBytes = readAllBytes(zip);
                    return parseManifestJson(new String(manifestBytes, StandardCharsets.UTF_8));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static ManifestInfo parseManifestJson(String json) {
        try {
            // Minimal JSON parsing without external deps: locate "header" object
            // and extract uuid, version array, name
            int headerStart = findKey(json, "\"header\"");
            if (headerStart < 0) return null;
            int objStart = json.indexOf('{', headerStart);
            if (objStart < 0) return null;
            String headerSection = extractObject(json, objStart);
            if (headerSection == null) return null;

            String uuidStr = extractStringValue(headerSection, "uuid");
            if (uuidStr == null) return null;
            UUID uuid = UUID.fromString(uuidStr);

            int[] version = {1, 0, 0};
            int[] parsed = extractIntArray(headerSection, "version");
            if (parsed != null && parsed.length >= 3) {
                version = parsed;
            }

            String name = extractStringValue(headerSection, "name");
            if (name == null || name.isBlank()) name = "Resource Pack";

            return new ManifestInfo(uuid, version, name);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int findKey(String json, String key) {
        int i = json.indexOf(key);
        return i;
    }

    private static String extractObject(String json, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return json.substring(openBrace, i + 1);
            }
        }
        return null;
    }

    private static String extractStringValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return null;
        int colon = json.indexOf(':', keyIdx + searchKey.length());
        if (colon < 0) return null;
        int quoteStart = json.indexOf('"', colon + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static int[] extractIntArray(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return null;
        int colon = json.indexOf(':', keyIdx + searchKey.length());
        if (colon < 0) return null;
        int bracketStart = json.indexOf('[', colon + 1);
        if (bracketStart < 0) return null;
        int bracketEnd = json.indexOf(']', bracketStart + 1);
        if (bracketEnd < 0) return null;
        String[] parts = json.substring(bracketStart + 1, bracketEnd).split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        List<byte[]> chunks = new ArrayList<>();
        int total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            byte[] chunk = new byte[n];
            System.arraycopy(buffer, 0, chunk, 0, n);
            chunks.add(chunk);
            total += n;
        }
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, pos, chunk.length);
            pos += chunk.length;
        }
        return result;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isEmpty() {
        return packs.isEmpty();
    }

    public List<ProxyResourcePackEntry> packs() {
        return packs;
    }

    public ProxyResourcePackEntry findByUuid(UUID uuid) {
        return packsByUuid.get(uuid);
    }

    public boolean isProxyPack(UUID uuid) {
        return packsByUuid.containsKey(uuid);
    }

    /**
     * Build a ResourcePacksInfoPacket containing only the proxy packs (for Phase 1 handshake).
     */
    public ResourcePacksInfoPacket buildProxyOnlyInfo() {
        ResourcePacksInfoPacket packet = new ResourcePacksInfoPacket();
        packet.setWorldTemplateId(new UUID(0, 0));
        packet.setWorldTemplateVersion("");
        for (ProxyResourcePackEntry pack : packs) {
            packet.getResourcePackInfos().add(pack.toInfoEntry());
        }
        return packet;
    }

    /**
     * Build a merged ResourcePacksInfoPacket with proxy packs prepended to backend packs.
     * UUID conflicts are resolved by version: the newer version wins.
     */
    public ResourcePacksInfoPacket buildMergedInfo(ResourcePacksInfoPacket backendInfo) {
        ResourcePacksInfoPacket merged = new ResourcePacksInfoPacket();
        merged.setForcedToAccept(backendInfo.isForcedToAccept());
        merged.setScriptingEnabled(backendInfo.isScriptingEnabled());
        merged.setForcingServerPacksEnabled(backendInfo.isForcingServerPacksEnabled());
        merged.setHasAddonPacks(backendInfo.isHasAddonPacks());
        merged.setWorldTemplateId(backendInfo.getWorldTemplateId() != null
                ? backendInfo.getWorldTemplateId() : new UUID(0, 0));
        merged.setWorldTemplateVersion(backendInfo.getWorldTemplateVersion() != null
                ? backendInfo.getWorldTemplateVersion() : "");

        // Index backend resource packs by UUID
        Map<UUID, ResourcePacksInfoPacket.Entry> backendByUuid = new LinkedHashMap<>();
        for (ResourcePacksInfoPacket.Entry e : backendInfo.getResourcePackInfos()) {
            backendByUuid.put(e.getPackId(), e);
        }

        // Process proxy packs: add them (winning UUID conflicts by version)
        Set<UUID> addedFromProxy = new HashSet<>();
        for (ProxyResourcePackEntry proxyPack : packs) {
            ResourcePacksInfoPacket.Entry conflict = backendByUuid.get(proxyPack.uuid());
            if (conflict != null) {
                int[] backendVer = parseVersion(conflict.getPackVersion());
                if (compareVersions(proxyPack.version(), backendVer) >= 0) {
                    // Proxy version wins (or tie): use proxy pack.
                    //
                    // Silent. This is per pack, per join, and the ordinary case is a tie — five
                    // identical lines every time anyone connects. The genuinely interesting variant,
                    // where the two versions actually differ, is not worth reinstating here either:
                    // it would still repeat on every join. If that needs reporting, do it once when
                    // the registry loads, comparing against the backend's advertised set.
                    merged.getResourcePackInfos().add(proxyPack.toInfoEntry());
                    backendByUuid.remove(proxyPack.uuid());
                }
                // else: backend version wins; backend entry stays in backendByUuid
            } else {
                merged.getResourcePackInfos().add(proxyPack.toInfoEntry());
            }
            addedFromProxy.add(proxyPack.uuid());
        }

        // Add remaining backend resource packs (those not displaced by proxy)
        for (ResourcePacksInfoPacket.Entry e : backendInfo.getResourcePackInfos()) {
            if (backendByUuid.containsKey(e.getPackId())) {
                merged.getResourcePackInfos().add(e);
            }
        }

        // Behavior packs: pass through unchanged
        merged.getBehaviorPackInfos().addAll(backendInfo.getBehaviorPackInfos());
        return merged;
    }

    /**
     * Build a merged ResourcePackStackPacket injecting proxy packs.
     * UUID conflicts resolved by version (newer wins).
     */
    public ResourcePackStackPacket buildMergedStack(ResourcePackStackPacket backendStack) {
        ResourcePackStackPacket merged = new ResourcePackStackPacket();
        merged.setForcedToAccept(backendStack.isForcedToAccept());
        merged.setGameVersion(backendStack.getGameVersion());
        merged.setExperimentsPreviouslyToggled(backendStack.isExperimentsPreviouslyToggled());
        merged.getExperiments().addAll(backendStack.getExperiments());

        // Index backend resource stack by UUID string (lowercase)
        Map<String, ResourcePackStackPacket.Entry> backendStackByUuid = new LinkedHashMap<>();
        for (ResourcePackStackPacket.Entry e : backendStack.getResourcePacks()) {
            backendStackByUuid.put(e.getPackId().toLowerCase(Locale.ROOT), e);
        }

        // Add proxy packs to stack (resolving UUID conflicts)
        for (ProxyResourcePackEntry proxyPack : packs) {
            String uuidKey = proxyPack.uuid().toString().toLowerCase(Locale.ROOT);
            ResourcePackStackPacket.Entry conflict = backendStackByUuid.get(uuidKey);
            if (conflict != null) {
                int[] backendVer = parseVersion(conflict.getPackVersion());
                if (compareVersions(proxyPack.version(), backendVer) >= 0) {
                    // Proxy version wins: replace backend entry
                    merged.getResourcePacks().add(new ResourcePackStackPacket.Entry(
                            proxyPack.uuid().toString(), proxyPack.versionString(), ""
                    ));
                    backendStackByUuid.remove(uuidKey);
                }
                // else: backend version wins; backend entry stays
            } else {
                merged.getResourcePacks().add(new ResourcePackStackPacket.Entry(
                        proxyPack.uuid().toString(), proxyPack.versionString(), ""
                ));
            }
        }

        // Add remaining backend stack entries
        for (ResourcePackStackPacket.Entry e : backendStack.getResourcePacks()) {
            if (backendStackByUuid.containsKey(e.getPackId().toLowerCase(Locale.ROOT))) {
                merged.getResourcePacks().add(e);
            }
        }

        merged.getBehaviorPacks().addAll(backendStack.getBehaviorPacks());
        return merged;
    }

    /** Send ResourcePackDataInfoPacket to client for a proxy pack. */
    public void sendDataInfo(ListenerSession client, UUID packId) {
        ProxyResourcePackEntry pack = packsByUuid.get(packId);
        if (pack == null) return;

        long chunkCount = (long) Math.ceil((double) pack.data().length / ProxyResourcePackEntry.CHUNK_SIZE);
        ResourcePackDataInfoPacket dataInfo = new ResourcePackDataInfoPacket();
        dataInfo.setPackId(packId);
        dataInfo.setPackVersion(pack.versionString());
        dataInfo.setMaxChunkSize(ProxyResourcePackEntry.CHUNK_SIZE);
        dataInfo.setChunkCount(chunkCount);
        dataInfo.setCompressedPackSize(pack.data().length);
        dataInfo.setHash(pack.hash());
        dataInfo.setPremium(false);
        dataInfo.setType(ResourcePackType.RESOURCES);
        client.sendPacket(dataInfo);
    }

    /** Send a ResourcePackChunkDataPacket to client for a proxy pack chunk. */
    public void sendChunk(ListenerSession client, UUID packId, int chunkIndex) {
        ProxyResourcePackEntry pack = packsByUuid.get(packId);
        if (pack == null) return;

        int start = chunkIndex * ProxyResourcePackEntry.CHUNK_SIZE;
        if (start >= pack.data().length) return;
        int end = Math.min(start + ProxyResourcePackEntry.CHUNK_SIZE, pack.data().length);
        byte[] chunk = Arrays.copyOfRange(pack.data(), start, end);

        ResourcePackChunkDataPacket chunkData = new ResourcePackChunkDataPacket();
        chunkData.setPackId(packId);
        chunkData.setPackVersion(pack.versionString());
        chunkData.setChunkIndex(chunkIndex);
        chunkData.setProgress((long) start);
        chunkData.setData(Unpooled.wrappedBuffer(chunk));
        client.sendPacket(chunkData);
    }

    // ---- version helpers ----

    static int[] parseVersion(String version) {
        if (version == null || version.isEmpty()) return new int[]{0, 0, 0};
        String[] parts = version.split("\\.", -1);
        int[] result = {0, 0, 0};
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            try {
                result[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(a[i], b[i]);
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    // ---- inner records ----

    private record ManifestInfo(UUID uuid, int[] version, String name) {
    }
}
