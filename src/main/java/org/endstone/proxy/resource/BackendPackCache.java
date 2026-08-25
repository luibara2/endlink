package org.endstone.proxy.resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

/**
 * Backend resource packs the proxy has seen, kept on disk so it can serve them itself.
 *
 * <p>A client downloads packs during its login handshake and never again, so a backend joined
 * mid-session cannot ask for its packs: the proxy answers that handshake on the player's behalf and
 * the packs are simply missing for them. Serving every backend's packs at login is the fix, and this
 * is what removes the manual step of copying each one into {@code resourcePacks.dir} — the proxy
 * keeps a copy the first time it sees the bytes, and every login after that includes them.</p>
 *
 * <p>Files are named {@code <uuid>_<version>.mcpack}, so a pack whose version is bumped is a new file
 * rather than an overwrite, and an operator can see at a glance what the proxy has learned. The
 * bytes are exactly what the backend sent, verified against the hash the backend advertised before
 * anything is written.</p>
 */
public final class BackendPackCache {
    /**
     * Refuse to buffer a pack larger than this. The download is held in memory to be hashed before it
     * is trusted, and it arrives from a backend on the pre-authentication side of nothing — the cap
     * is what stops one misconfigured backend from deciding the proxy's heap size.
     */
    public static final int MAX_PACK_BYTES = 96 * 1024 * 1024;

    /**
     * Largest expanded pack the proxy will inspect or cache. {@code ResourcePacksInfo.Entry#packSize}
     * is the sum of the files inside the archive, while {@link #MAX_PACK_BYTES} limits the compressed
     * bytes that are actually buffered from {@code ResourcePackDataInfoPacket}. Keeping both limits
     * prevents a small zip bomb from bypassing the download cap.
     */
    public static final long MAX_PACK_CONTENT_BYTES = 512L * 1024 * 1024;

    private final Path directory;
    private final ProxyResourcePackRegistry registry;

    private BackendPackCache(Path directory, ProxyResourcePackRegistry registry) {
        this.directory = directory;
        this.registry = registry;
    }

    /** A cache that remembers nothing, for {@code resourcePacks.cacheBackendPacks=false}. */
    public static BackendPackCache disabled() {
        return new BackendPackCache(null, null);
    }

    public static BackendPackCache of(Path directory, ProxyResourcePackRegistry registry) {
        return new BackendPackCache(directory, registry);
    }

    public boolean isEnabled() {
        return directory != null && registry != null;
    }

    /** True when this pack is already served by the proxy, from the cache or from the packs directory. */
    public boolean has(UUID packId, int[] version) {
        if (registry == null || packId == null) {
            return false;
        }
        ProxyResourcePackEntry existing = registry.findByUuid(packId);
        return existing != null
                && ProxyResourcePackRegistry.compareVersions(existing.version(), version) >= 0;
    }

    /**
     * True when the copy the proxy holds is the one the backend is serving right now.
     *
     * <p>{@link #has} answers a weaker question and is why a pack could go stale for ever. A pack
     * edited in place keeps its {@code manifest.json} version, so a version comparison says the
     * cached copy is current, the proxy never looks at the bytes again, and every player is served
     * the previous edit - a manifest that lists items whose textures are no longer in the file, which
     * the client draws as nothing at all. The backend tells us the size, and the download tells us the
     * hash; either disagreeing with what is cached means the copy is out of date.</p>
     *
     * @param advertisedSize the expanded content size from {@code ResourcePacksInfoPacket}, or 0
     *                       when the backend did not say
     * @param advertisedHash the hash the backend advertised, or null when it did not say
     */
    public boolean hasCurrentContent(UUID packId, int[] version, long advertisedSize, byte[] advertisedHash) {
        if (registry == null || packId == null) {
            return false;
        }
        ProxyResourcePackEntry existing = registry.findByUuid(packId);
        if (existing == null) {
            return false;
        }
        int comparison = ProxyResourcePackRegistry.compareVersions(existing.version(), version);
        if (comparison < 0) {
            return false;
        }
        if (comparison > 0) {
            // The proxy has a newer version than the backend advertises; nothing to relearn.
            return true;
        }
        if (advertisedHash != null && advertisedHash.length > 0) {
            return java.util.Arrays.equals(advertisedHash, existing.hash());
        }
        return advertisedSize <= 0 || advertisedSize == existing.contentSize();
    }

    /**
     * The equivalent check for {@code ResourcePackDataInfoPacket}, whose size is the compressed
     * archive length rather than the expanded content size advertised by {@code ResourcePacksInfo}.
     */
    public boolean hasCurrentCompressed(
            UUID packId,
            int[] version,
            long advertisedCompressedSize,
            byte[] advertisedHash
    ) {
        if (registry == null || packId == null) {
            return false;
        }
        ProxyResourcePackEntry existing = registry.findByUuid(packId);
        if (existing == null) {
            return false;
        }
        int comparison = ProxyResourcePackRegistry.compareVersions(existing.version(), version);
        if (comparison < 0) {
            return false;
        }
        if (comparison > 0) {
            return true;
        }
        if (advertisedHash != null && advertisedHash.length > 0) {
            return java.util.Arrays.equals(advertisedHash, existing.hash());
        }
        return advertisedCompressedSize <= 0 || advertisedCompressedSize == existing.data().length;
    }

    /**
     * Verifies, stores and starts serving a pack downloaded from a backend.
     *
     * @param expectedHash the hash the backend advertised, or null if it advertised none
     * @return true when the pack was accepted
     */
    public boolean store(UUID packId, byte[] data, byte[] expectedHash) {
        if (!isEnabled() || packId == null || data == null || data.length == 0) {
            return false;
        }
        ProxyResourcePackEntry entry = ProxyResourcePackRegistry.entryFrom(data);
        if (entry == null) {
            System.out.printf("Not caching backend pack %s: it has no readable manifest.json.%n", packId);
            return false;
        }
        if (!entry.uuid().equals(packId)) {
            // A pack whose manifest disagrees with the id it was served under would be served to
            // clients under the wrong identity, which is how one backend's pack silently shadows
            // another's.
            System.out.printf(
                    "Not caching backend pack %s: its manifest claims uuid %s.%n", packId, entry.uuid());
            return false;
        }
        if (expectedHash != null && expectedHash.length > 0
                && !java.util.Arrays.equals(expectedHash, entry.hash())) {
            System.out.printf(
                    "Not caching backend pack %s v%s: the download does not match the hash the backend "
                            + "advertised.%n",
                    packId, entry.versionString());
            return false;
        }
        ProxyResourcePackEntry previous = registry.findByUuid(packId);
        if (!registry.learn(entry)) {
            return false;
        }
        write(entry);
        boolean replacedSameVersion = previous != null
                && ProxyResourcePackRegistry.compareVersions(previous.version(), entry.version()) == 0;
        if (replacedSameVersion) {
            System.out.printf(
                    "Replaced the cached copy of resource pack %s v%s (uuid=%s): it was %d bytes, the backend "
                            + "now serves %d. The old copy was being sent to every player in place of the "
                            + "backend's, which is what makes items lose their textures.%n",
                    entry.name(), entry.versionString(), entry.uuid(),
                    previous.data().length, entry.data().length);
        } else {
            System.out.printf(
                    "Cached resource pack %s v%s (uuid=%s, %d bytes) from a backend; every client that logs in "
                            + "from now on gets it.%n",
                    entry.name(), entry.versionString(), entry.uuid(), entry.data().length);
        }
        return true;
    }

    private void write(ProxyResourcePackEntry entry) {
        Path target = directory.resolve(
                entry.uuid().toString().toLowerCase(Locale.ROOT) + "_" + entry.versionString() + ".mcpack");
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(directory);
            Files.write(temporary, entry.data());
            // Replaced in one step: a half-written pack read back at the next start would be served to
            // clients as though it were whole.
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.printf("Could not write cached resource pack %s: %s%n", target, e.getMessage());
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }
}
