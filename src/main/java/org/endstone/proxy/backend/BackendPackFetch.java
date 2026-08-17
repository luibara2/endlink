package org.endstone.proxy.backend;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackChunkDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackChunkRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackDataInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.endstone.proxy.resource.BackendPackCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Downloads a backend's resource packs on the proxy's own behalf, during a mid-session switch.
 *
 * <p>The proxy only ever sees pack bytes when a client downloads them, and a client downloads them
 * only during its login handshake. A backend a player switches to is therefore never asked for its
 * packs by anyone: the proxy answers that handshake itself, and the packs stay unknown no matter how
 * many players go there. This plays the client's part of the handshake — request the packs, pull the
 * chunks, hand them to the cache — so the backend is learned once and every later login includes its
 * packs.</p>
 *
 * <p>It costs the first switching player a wait, which is why it is bounded twice over: a pack larger
 * than {@link BackendPackCache#MAX_PACK_BYTES} is skipped outright, and if the backend stops
 * answering the whole fetch is abandoned. Either way the switch continues — a player waiting forever
 * for a texture is a worse failure than a missing texture.</p>
 */
final class BackendPackFetch {
    private final BackendPackCache cache;
    private final String backendName;
    private final Consumer<BedrockPacket> toBackend;
    private final Runnable onFinished;

    private final Map<UUID, Wanted> wanted = new LinkedHashMap<>();
    private final Map<UUID, Download> downloads = new HashMap<>();
    private boolean finished;

    private BackendPackFetch(
            BackendPackCache cache,
            String backendName,
            Consumer<BedrockPacket> toBackend,
            Runnable onFinished
    ) {
        this.cache = cache;
        this.backendName = backendName;
        this.toBackend = toBackend;
        this.onFinished = onFinished;
    }

    /**
     * Starts a fetch for everything in {@code packsInfo} the proxy cannot already serve, or returns
     * null when there is nothing to fetch and the caller should complete the handshake as before.
     */
    static BackendPackFetch start(
            BackendPackCache cache,
            String backendName,
            ResourcePacksInfoPacket packsInfo,
            Consumer<BedrockPacket> toBackend,
            Runnable onFinished
    ) {
        if (!cache.isEnabled()) {
            return null;
        }
        BackendPackFetch fetch = new BackendPackFetch(cache, backendName, toBackend, onFinished);
        List<String> requestIds = new ArrayList<>();
        for (ResourcePacksInfoPacket.Entry entry : packsInfo.getResourcePackInfos()) {
            UUID packId = entry.getPackId();
            if (packId == null) {
                continue;
            }
            int[] version = parseVersion(entry.getPackVersion());
            if (cache.has(packId, version)) {
                continue;
            }
            if (entry.getContentKey() != null && !entry.getContentKey().isEmpty()) {
                // An encrypted pack is useless to anyone but the backend that holds the key; storing
                // it would only mean serving bytes no client can open.
                System.out.printf(
                        "Not caching encrypted resource pack %s from backend %s; copy it into "
                                + "resourcePacks.dir if players need it after a switch.%n",
                        packId, backendName);
                continue;
            }
            if (entry.getPackSize() > BackendPackCache.MAX_PACK_BYTES) {
                System.out.printf(
                        "Not caching resource pack %s from backend %s: %d bytes is over the %d byte limit.%n",
                        packId, backendName, entry.getPackSize(), BackendPackCache.MAX_PACK_BYTES);
                continue;
            }
            fetch.wanted.put(packId, new Wanted(packId, entry.getPackVersion()));
            requestIds.add(packId + "_" + entry.getPackVersion());
        }
        if (requestIds.isEmpty()) {
            return null;
        }
        System.out.printf(
                "Downloading %d resource pack(s) from backend %s so later logins can serve them; the player "
                        + "switching now waits for it once.%n",
                requestIds.size(), backendName);
        ResourcePackClientResponsePacket request = new ResourcePackClientResponsePacket();
        request.setStatus(ResourcePackClientResponsePacket.Status.SEND_PACKS);
        request.getPackIds().addAll(requestIds);
        toBackend.accept(request);
        return fetch;
    }

    boolean isFinished() {
        return finished;
    }

    /** @return true when the packet belonged to this fetch and must not reach the client */
    boolean handle(BedrockPacket packet) {
        if (finished) {
            return false;
        }
        if (packet instanceof ResourcePackDataInfoPacket dataInfo) {
            return beginDownload(dataInfo);
        }
        if (packet instanceof ResourcePackChunkDataPacket chunkData) {
            return acceptChunk(chunkData);
        }
        return false;
    }

    private boolean beginDownload(ResourcePackDataInfoPacket dataInfo) {
        Wanted pack = wanted.get(dataInfo.getPackId());
        if (pack == null) {
            return false;
        }
        long size = dataInfo.getCompressedPackSize();
        if (size <= 0 || size > BackendPackCache.MAX_PACK_BYTES) {
            System.out.printf(
                    "Giving up on resource pack %s from backend %s: it reports %d bytes.%n",
                    dataInfo.getPackId(), backendName, size);
            wanted.remove(dataInfo.getPackId());
            finishIfDone();
            return true;
        }
        downloads.put(dataInfo.getPackId(), new Download(
                new byte[(int) size],
                dataInfo.getHash(),
                Math.max(1, dataInfo.getMaxChunkSize())
        ));
        requestChunk(dataInfo.getPackId(), 0);
        return true;
    }

    private boolean acceptChunk(ResourcePackChunkDataPacket chunkData) {
        Download download = downloads.get(chunkData.getPackId());
        if (download == null) {
            return false;
        }
        ByteBuf data = chunkData.getData();
        int offset = (int) Math.min(download.buffer.length, (long) chunkData.getChunkIndex() * download.chunkSize);
        int length = data == null ? 0 : Math.min(data.readableBytes(), download.buffer.length - offset);
        if (length > 0) {
            // Read without consuming: this packet is the backend's, and releasing or draining it here
            // would corrupt anything downstream that still expects it intact.
            data.getBytes(data.readerIndex(), download.buffer, offset, length);
            download.filled += length;
        }
        if (download.filled >= download.buffer.length) {
            downloads.remove(chunkData.getPackId());
            wanted.remove(chunkData.getPackId());
            cache.store(chunkData.getPackId(), download.buffer, download.hash);
            finishIfDone();
        } else {
            requestChunk(chunkData.getPackId(), chunkData.getChunkIndex() + 1);
        }
        return true;
    }

    private void requestChunk(UUID packId, int chunkIndex) {
        ResourcePackChunkRequestPacket request = new ResourcePackChunkRequestPacket();
        request.setPackId(packId);
        request.setPackVersion(wanted.get(packId).version());
        request.setChunkIndex(chunkIndex);
        toBackend.accept(request);
    }

    private void finishIfDone() {
        if (wanted.isEmpty()) {
            finish();
        }
    }

    /** Ends the fetch, kept or not, and lets the switch handshake continue. */
    void finish() {
        if (finished) {
            return;
        }
        finished = true;
        downloads.clear();
        wanted.clear();
        onFinished.run();
    }

    void abandon(String reason) {
        if (finished) {
            return;
        }
        System.out.printf(
                "Stopped downloading resource packs from backend %s (%s); the switch continues and the packs "
                        + "stay unserved until next time.%n",
                backendName, reason);
        finish();
    }

    private static int[] parseVersion(String version) {
        return org.endstone.proxy.resource.ProxyResourcePackRegistry.parseVersion(version);
    }

    private record Wanted(UUID packId, String version) {
    }

    private static final class Download {
        private final byte[] buffer;
        private final byte[] hash;
        private final int chunkSize;
        private int filled;

        private Download(byte[] buffer, byte[] hash, long chunkSize) {
            this.buffer = buffer;
            this.hash = hash;
            this.chunkSize = (int) Math.min(Integer.MAX_VALUE, chunkSize);
        }
    }
}
