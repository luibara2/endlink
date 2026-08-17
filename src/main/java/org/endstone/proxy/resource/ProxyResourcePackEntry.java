package org.endstone.proxy.resource;

import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;

import java.util.UUID;

public record ProxyResourcePackEntry(
        UUID uuid,
        int[] version,
        String name,
        byte[] data,
        byte[] hash
) {
    /**
     * Bytes per {@code ResourcePackChunkDataPacket}. 100KB rather than a megabyte on purpose.
     *
     * <p>The client asks for one chunk at a time, so this is also the burst size: a 1MB chunk leaves
     * RakNet with ~750 datagrams to send at once, and the client's acknowledgements for them arrive
     * back in a handful of ticks. That inbound burst is counted by the per-address packet limiter
     * ({@code security.rateLimit.packetLimit}, 120 datagrams per address per tick), which blocks the
     * address mid-login; the handshake then stalls and the player times out before they ever join.
     * Smaller chunks spread the same bytes over more request/response round trips, so the return
     * traffic stays inside the budget that protects the public listener.</p>
     */
    static final int CHUNK_SIZE = 100 * 1024;

    public String versionString() {
        return version[0] + "." + version[1] + "." + version[2];
    }

    public ResourcePacksInfoPacket.Entry toInfoEntry() {
        // Local build constructor: (UUID, String version, long size,
        // String contentKey, String subPackName, String contentId,
        // boolean scripting, boolean raytracingCapable, boolean addonPack, String cdnUrl)
        return new ResourcePacksInfoPacket.Entry(
                uuid,
                versionString(),
                (long) data.length,
                "",             // contentKey
                name,           // subPackName
                uuid.toString(), // contentId
                false,          // scripting
                false,          // raytracingCapable
                false,          // addonPack
                ""              // cdnUrl
        );
    }
}
