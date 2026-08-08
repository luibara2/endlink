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
    static final int CHUNK_SIZE = 1024 * 1024;

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
