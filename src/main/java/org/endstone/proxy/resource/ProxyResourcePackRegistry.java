package org.endstone.proxy.resource;

import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.data.ResourcePackType;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackChunkDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackDataInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.endstone.proxy.listener.ListenerSession;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.zip.ZipOutputStream;

public final class ProxyResourcePackRegistry {
    private static final ProxyResourcePackRegistry EMPTY = new ProxyResourcePackRegistry(Collections.emptyList());
    private static final String MANIFEST = "manifest.json";
    /** 2000-01-01T00:00:00Z. Any fixed value inside the DOS-time range keeps folder zips reproducible. */
    private static final long ZIP_TIMESTAMP = 946684800000L;

    /**
     * Read on every join and written when a backend's pack is learned, so both are snapshots swapped
     * under a lock rather than collections mutated in place: a join in flight keeps the set it
     * started with instead of seeing half an update.
     */
    private volatile List<ProxyResourcePackEntry> packs;
    private volatile Map<UUID, ProxyResourcePackEntry> packsByUuid;
    /**
     * Packs an operator put in {@code resourcePacks.dir} by hand. A backend's copy never replaces one
     * of these, however much the bytes differ: the whole point of putting a pack there is to override
     * what the backend serves, and {@link #learn} would otherwise undo that on the first join.
     */
    private volatile Set<UUID> operatorProvided = Set.of();
    /**
     * Stale copies already named, so a pack that cannot be relearned says so once instead of on every
     * join. Nothing depends on this but the log line: the merge itself falls back to letting the
     * backend serve the pack, which is what a direct connection does anyway.
     */
    private final Set<String> reportedStale = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private ProxyResourcePackRegistry(List<ProxyResourcePackEntry> packs) {
        install(packs);
    }

    private void install(List<ProxyResourcePackEntry> packs) {
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

    /** A registry that starts empty but can still learn packs; see {@link #add}. */
    public static ProxyResourcePackRegistry mutableEmpty() {
        return new ProxyResourcePackRegistry(Collections.emptyList());
    }

    /**
     * Adds a pack learned at runtime, keeping the newer of the two when the uuid is already known.
     *
     * <p>Refused on the shared {@link #EMPTY} instance, which every packless proxy holds: adding to it
     * would hand one connection's packs to every other.</p>
     *
     * @return true when the registry changed
     */
    public synchronized boolean add(ProxyResourcePackEntry entry) {
        if (this == EMPTY || entry == null) {
            return false;
        }
        ProxyResourcePackEntry existing = packsByUuid.get(entry.uuid());
        if (existing != null && compareVersions(existing.version(), entry.version()) >= 0) {
            return false;
        }
        installReplacing(entry);
        return true;
    }

    /**
     * Replaces {@code entry}'s uuid where it already sits, or appends it when it is new.
     *
     * <p>Position matters: the order of this list becomes the order of the pack stack the client is
     * given, and the pack stack is what decides which pack's copy of a shared file wins. A pack that
     * jumped to the end when it was relearned would silently change every later join's texture
     * precedence.</p>
     */
    private void installReplacing(ProxyResourcePackEntry entry) {
        List<ProxyResourcePackEntry> updated = new ArrayList<>(packs.size() + 1);
        boolean replaced = false;
        for (ProxyResourcePackEntry pack : packs) {
            if (pack.uuid().equals(entry.uuid())) {
                updated.add(entry);
                replaced = true;
            } else {
                updated.add(pack);
            }
        }
        if (!replaced) {
            updated.add(entry);
        }
        install(updated);
    }

    /**
     * Takes a pack the proxy has just seen a backend serve, replacing a copy that is out of date.
     *
     * <p>{@link #add} compares versions alone, which is right while loading from disk but wrong for
     * anything learned at runtime: a pack edited in place keeps its {@code manifest.json} version, so
     * a version comparison says the cached copy is still current and the proxy goes on serving the
     * old bytes to every player, for ever. What a client then downloads from the proxy is not what
     * the backend has, and the difference shows up as items with no texture at all - the pack still
     * lists them, the files behind them are the previous edit's. Comparing the bytes is what makes
     * that self-correcting.</p>
     *
     * @return true when the registry changed
     */
    public synchronized boolean learn(ProxyResourcePackEntry entry) {
        if (this == EMPTY || entry == null) {
            return false;
        }
        ProxyResourcePackEntry existing = packsByUuid.get(entry.uuid());
        if (existing == null) {
            return add(entry);
        }
        int comparison = compareVersions(existing.version(), entry.version());
        if (comparison > 0) {
            return false;
        }
        if (comparison == 0) {
            if (Arrays.equals(existing.hash(), entry.hash())) {
                return false;
            }
            if (operatorProvided.contains(entry.uuid())) {
                System.out.printf(
                        "Backend copy of resource pack %s v%s differs from the one in the packs directory "
                                + "(%d bytes there, %d on the backend); keeping yours. Delete it from "
                                + "resourcePacks.dir if the backend's copy is the one players should get.%n",
                        entry.uuid(), entry.versionString(), existing.data().length, entry.data().length);
                return false;
            }
        }
        installReplacing(entry);
        return true;
    }

    /** Reads a pack from bytes exactly as a {@code .mcpack} on disk would be read. */
    public static ProxyResourcePackEntry entryFrom(byte[] data) {
        ManifestInfo manifest = parseManifest(data);
        if (manifest == null) {
            return null;
        }
        return new ProxyResourcePackEntry(manifest.uuid(), manifest.version(), manifest.name(), data, sha256(data));
    }

    /**
     * Loads the operator's packs plus the ones cached from backends, into a registry that can still
     * learn more while the proxy runs.
     *
     * <p>A pack placed in {@code dir} by hand wins a tie against the cached copy of the same version:
     * it is the one an operator can actually edit.</p>
     */
    public static ProxyResourcePackRegistry load(Path dir, Path cacheDir) {
        ProxyResourcePackRegistry registry = mutableEmpty();
        Set<UUID> pinned = new LinkedHashSet<>();
        for (ProxyResourcePackEntry entry : loadEntries(dir, "")) {
            if (registry.add(entry)) {
                pinned.add(entry.uuid());
            }
        }
        registry.operatorProvided = Set.copyOf(pinned);
        for (ProxyResourcePackEntry entry : loadEntries(cacheDir, ", cached from a backend")) {
            registry.add(entry);
        }
        return registry;
    }

    public static ProxyResourcePackRegistry load(Path dir) {
        List<ProxyResourcePackEntry> loaded = loadEntries(dir, "");
        if (loaded.isEmpty()) {
            return EMPTY;
        }
        return new ProxyResourcePackRegistry(loaded);
    }

    private static List<ProxyResourcePackEntry> loadEntries(Path dir, String origin) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<ProxyResourcePackEntry> loaded = new ArrayList<>();
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(ProxyResourcePackRegistry::looksLikePack)
                    .sorted()
                    .forEach(p -> {
                        try {
                            ProxyResourcePackEntry entry = Files.isDirectory(p) ? loadFolderPack(p) : loadPack(p);
                            if (entry != null) {
                                loaded.add(entry);
                                System.out.printf(
                                        "Loaded proxy resource pack: %s v%s (uuid=%s, %d bytes%s%s).%n",
                                        entry.name(), entry.versionString(), entry.uuid(), entry.data().length,
                                        Files.isDirectory(p) ? ", zipped from folder" : "",
                                        origin
                                );
                            }
                        } catch (Exception e) {
                            System.out.printf("Failed to load proxy resource pack %s: %s%n", p.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            System.out.printf("Failed to list proxy resource packs directory %s: %s%n", dir, e.getMessage());
            return List.of();
        }
        return loaded;
    }

    /** A packaged pack file, or a directory that could hold an unpackaged one. */
    private static boolean looksLikePack(Path path) {
        if (Files.isDirectory(path)) {
            return true;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mcpack") || name.endsWith(".zip");
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

    /**
     * Load an unpackaged pack: a directory holding manifest.json, zipped in memory so the rest of
     * the pipeline sees exactly what a .mcpack would have given it.
     *
     * <p>The zip is built deterministically — entries sorted, one fixed timestamp — so a pack that
     * did not change on disk keeps the same SHA-256 across restarts and clients do not redownload it.
     */
    private static ProxyResourcePackEntry loadFolderPack(Path dir) throws Exception {
        Path root = findManifestRoot(dir);
        if (root == null) {
            // Not every directory beside the packs is a pack; say nothing about the ones that aren't.
            return null;
        }
        ManifestInfo manifest = parseManifestJson(Files.readString(root.resolve(MANIFEST), StandardCharsets.UTF_8));
        if (manifest == null) {
            System.out.printf("Skipping %s: manifest.json is not a valid pack manifest.%n", dir.getFileName());
            return null;
        }
        byte[] data = zipDirectory(root);
        return new ProxyResourcePackEntry(manifest.uuid(), manifest.version(), manifest.name(), data, sha256(data));
    }

    /**
     * The directory the zip should be rooted at: the folder itself when manifest.json sits in it, or
     * its single subdirectory when the pack was unpacked one level down (the shape you get from
     * extracting a .mcpack that wrapped its contents in a folder).
     */
    private static Path findManifestRoot(Path dir) throws IOException {
        if (Files.isRegularFile(dir.resolve(MANIFEST))) {
            return dir;
        }
        List<Path> children;
        try (Stream<Path> paths = Files.list(dir)) {
            children = paths.sorted().toList();
        }
        Path nested = null;
        for (Path child : children) {
            if (Files.isDirectory(child) && Files.isRegularFile(child.resolve(MANIFEST))) {
                if (nested != null) {
                    // Several packs side by side: ambiguous, and picking one would hide the others.
                    System.out.printf(
                            "Skipping %s: it holds several packs; move each one into %s directly.%n",
                            dir.getFileName(), dir.getParent() == null ? "the packs directory" : dir.getParent().getFileName()
                    );
                    return null;
                }
                nested = child;
            }
        }
        return nested;
    }

    private static byte[] zipDirectory(Path root) throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(root)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(p -> !isJunk(p.getFileName().toString()))
                    .sorted()
                    .toList();
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Path file : files) {
                String name = root.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(ZIP_TIMESTAMP);
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static boolean isJunk(String fileName) {
        return fileName.equals(".DS_Store") || fileName.equalsIgnoreCase("Thumbs.db")
                || fileName.equalsIgnoreCase("desktop.ini");
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
     * What {@link #buildMergedInfo} decided: the packet to send, and which packs the proxy will
     * answer chunk requests for.
     *
     * <p>The set matters because the two halves of the handshake have to agree. Telling a client that
     * a pack comes from the backend and then serving it from the proxy anyway would hand it exactly
     * the bytes the merge just decided were wrong.</p>
     *
     * @param stale packs the proxy holds under the backend's version but at a different size, named
     *              for reporting; the backend serves those and the proxy re-learns them
     */
    public record MergedPacksInfo(
            ResourcePacksInfoPacket packet,
            Set<UUID> servedByProxy,
            List<String> stale
    ) {
    }

    /**
     * Builds the pack list to send a client: the backend's own list, in the backend's order, with the
     * proxy's copy substituted wherever the proxy can serve it, and the packs only other backends use
     * appended after.
     *
     * <p>Two things here are deliberate and were not before.</p>
     *
     * <p><b>The backend's order is kept.</b> A pack stack is a precedence order - where two packs
     * carry the same file, one of them wins - so reordering it changes which textures a player sees.
     * Emitting the proxy's packs first, in whatever order the cache directory happened to list them,
     * gave a proxied join a different precedence order from a direct one for no reason. Packs the
     * backend does not list cannot displace anything if they go last.</p>
     *
     * <p><b>A cached copy that no longer matches the backend's is not served.</b> The backend
     * advertises each pack's size; if the proxy's copy of the same version is a different size it is a
     * previous edit of that pack, and serving it means the client gets a manifest listing content the
     * files behind it no longer contain. The backend's entry is forwarded instead, so the client
     * downloads the current bytes and {@code captureBackendPackBytes} re-caches them for everyone
     * else.</p>
     */
    public MergedPacksInfo buildMergedInfo(ResourcePacksInfoPacket backendInfo) {
        ResourcePacksInfoPacket merged = new ResourcePacksInfoPacket();
        merged.setForcedToAccept(backendInfo.isForcedToAccept());
        merged.setScriptingEnabled(backendInfo.isScriptingEnabled());
        merged.setForcingServerPacksEnabled(backendInfo.isForcingServerPacksEnabled());
        merged.setHasAddonPacks(backendInfo.isHasAddonPacks());
        // Carried over rather than defaulted: a backend turning vibrant visuals off is a decision
        // about how its packs are meant to be rendered, and dropping it renders them another way.
        merged.setVibrantVisualsForceDisabled(backendInfo.isVibrantVisualsForceDisabled());
        merged.setWorldTemplateId(backendInfo.getWorldTemplateId() != null
                ? backendInfo.getWorldTemplateId() : new UUID(0, 0));
        merged.setWorldTemplateVersion(backendInfo.getWorldTemplateVersion() != null
                ? backendInfo.getWorldTemplateVersion() : "");

        Set<UUID> servedByProxy = new LinkedHashSet<>();
        List<String> stale = new ArrayList<>();
        Set<UUID> listedByBackend = new HashSet<>();

        for (ResourcePacksInfoPacket.Entry backendEntry : backendInfo.getResourcePackInfos()) {
            UUID packId = backendEntry.getPackId();
            if (packId != null) {
                listedByBackend.add(packId);
            }
            ProxyResourcePackEntry proxyPack = packId == null ? null : packsByUuid.get(packId);
            if (proxyPack == null) {
                merged.getResourcePackInfos().add(backendEntry);
                continue;
            }
            int comparison = compareVersions(proxyPack.version(), parseVersion(backendEntry.getPackVersion()));
            if (comparison < 0) {
                // The backend has the newer one; it serves it, and the proxy learns it on the way past.
                merged.getResourcePackInfos().add(backendEntry);
                continue;
            }
            if (comparison == 0) {
                if (backendEntry.getContentKey() != null && !backendEntry.getContentKey().isEmpty()) {
                    // Encrypted: the bytes are useless without the key, and only the backend sends it.
                    merged.getResourcePackInfos().add(backendEntry);
                    continue;
                }
                long advertised = backendEntry.getPackSize();
                if (advertised > 0 && advertised != proxyPack.data().length) {
                    String description = proxyPack.name() + " v" + proxyPack.versionString()
                            + " (" + proxyPack.data().length + " bytes cached, "
                            + advertised + " on the backend)";
                    if (reportedStale.add(description)) {
                        stale.add(description);
                    }
                    merged.getResourcePackInfos().add(backendEntry);
                    continue;
                }
            }
            merged.getResourcePackInfos().add(asProxyServed(proxyPack, backendEntry));
            servedByProxy.add(packId);
        }

        // Packs this backend does not use, learned from the others. They go last so they cannot take
        // precedence over anything the backend actually asked for.
        for (ProxyResourcePackEntry proxyPack : packs) {
            if (!listedByBackend.contains(proxyPack.uuid())) {
                merged.getResourcePackInfos().add(proxyPack.toInfoEntry());
                servedByProxy.add(proxyPack.uuid());
            }
        }

        // Behavior packs: pass through unchanged.
        merged.getBehaviorPackInfos().addAll(backendInfo.getBehaviorPackInfos());
        return new MergedPacksInfo(merged, Set.copyOf(servedByProxy), List.copyOf(stale));
    }

    /**
     * The proxy's copy of a pack, described the way the backend described it.
     *
     * <p>Everything but the size and the download source is the backend's own metadata. These fields
     * change how the client treats a pack - whether it expects a sub-pack, whether it belongs to an
     * addon, whether scripts run - and inventing values for them is how a pack that works on a direct
     * connection stops working through the proxy.</p>
     */
    private static ResourcePacksInfoPacket.Entry asProxyServed(
            ProxyResourcePackEntry proxyPack,
            ResourcePacksInfoPacket.Entry backendEntry
    ) {
        return new ResourcePacksInfoPacket.Entry(
                proxyPack.uuid(),
                proxyPack.versionString(),
                (long) proxyPack.data().length,
                backendEntry.getContentKey() == null ? "" : backendEntry.getContentKey(),
                backendEntry.getSubPackName() == null ? "" : backendEntry.getSubPackName(),
                backendEntry.getContentId() == null ? "" : backendEntry.getContentId(),
                backendEntry.isScripting(),
                backendEntry.isRaytracingCapable(),
                backendEntry.isAddonPack(),
                // Emptied on purpose: a CDN url tells the client to fetch the pack from somewhere else,
                // and the copy being described is the one this proxy is about to send over the wire.
                ""
        );
    }

    /**
     * Builds the pack stack to send a client: the backend's stack, in its order, plus stack entries
     * for the packs only other backends use.
     *
     * <p>The stack is the precedence order the client applies, so it is the backend's to decide. All
     * the proxy contributes is the extra packs, appended, and a version correction where its own copy
     * of a pack is the newer one.</p>
     */
    public ResourcePackStackPacket buildMergedStack(ResourcePackStackPacket backendStack) {
        ResourcePackStackPacket merged = new ResourcePackStackPacket();
        merged.setForcedToAccept(backendStack.isForcedToAccept());
        merged.setGameVersion(backendStack.getGameVersion());
        merged.setExperimentsPreviouslyToggled(backendStack.isExperimentsPreviouslyToggled());
        merged.getExperiments().addAll(backendStack.getExperiments());

        Set<UUID> listedByBackend = new HashSet<>();
        for (ResourcePackStackPacket.Entry backendEntry : backendStack.getResourcePacks()) {
            UUID packId = parseUuid(backendEntry.getPackId());
            if (packId != null) {
                listedByBackend.add(packId);
            }
            ProxyResourcePackEntry proxyPack = packId == null ? null : packsByUuid.get(packId);
            if (proxyPack != null
                    && compareVersions(proxyPack.version(), parseVersion(backendEntry.getPackVersion())) > 0) {
                merged.getResourcePacks().add(new ResourcePackStackPacket.Entry(
                        backendEntry.getPackId(), proxyPack.versionString(), backendEntry.getSubPackName()
                ));
            } else {
                merged.getResourcePacks().add(backendEntry);
            }
        }

        for (ProxyResourcePackEntry proxyPack : packs) {
            if (!listedByBackend.contains(proxyPack.uuid())) {
                merged.getResourcePacks().add(new ResourcePackStackPacket.Entry(
                        proxyPack.uuid().toString(), proxyPack.versionString(), ""
                ));
            }
        }

        merged.getBehaviorPacks().addAll(backendStack.getBehaviorPacks());
        return merged;
    }

    private static UUID parseUuid(String packId) {
        if (packId == null || packId.isEmpty()) {
            return null;
        }
        int underscore = packId.indexOf('_');
        try {
            return UUID.fromString(underscore >= 0 ? packId.substring(0, underscore) : packId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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

    public static int[] parseVersion(String version) {
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

    public static int compareVersions(int[] a, int[] b) {
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
