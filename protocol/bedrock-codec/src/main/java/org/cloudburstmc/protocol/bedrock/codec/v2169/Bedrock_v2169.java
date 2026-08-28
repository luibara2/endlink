package org.cloudburstmc.protocol.bedrock.codec.v2169;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;

/**
 * Minecraft 1.26.45, network protocol 2169.
 *
 * <p>A hotfix release. Against 1.26.44 the format moves in exactly one place — {@code RemoveScore}
 * loses the constant {@code true} it gained at 1.26.44 — so every serializer, type map, entity data
 * layout and sound/particle id is inherited from {@link Bedrock_v2168} unchanged, and the difference
 * lives entirely in {@link BedrockCodecHelper_v2169}.
 *
 * <p>Renumbering is the point of this release. 1.26.40 through 1.26.44 all shipped as 2168 and did
 * <em>not</em> all agree on that field, which forced the proxy to key the shape off the peer's
 * Minecraft version string. 2169 puts the shape back in the protocol number where it belongs.
 *
 * <p>Verified against Mojang's own schema dumps: the diff from the 1.26.44.3 dump to the 1.26.45.1
 * dump touches three files — {@code types/RemoveScore.json}, the protocol number constraint in
 * {@code packets/RequestNetworkSettingsPacket.json}, and the README. Corroborated by gophertunnel
 * (Sandertv/gophertunnel#511), which reverts the same field to a single optional and deletes the
 * game-version workaround 1.26.44 needed.
 */
public class Bedrock_v2169 extends Bedrock_v2168 {

    public static final BedrockCodec CODEC = Bedrock_v2168.CODEC.toBuilder()
            .protocolVersion(2169)
            .minecraftVersion("1.26.45")
            .helper(() -> new BedrockCodecHelper_v2169(ENTITY_DATA, GAME_RULE_TYPES, ITEM_STACK_REQUEST_TYPES,
                    CONTAINER_SLOT_TYPES, PLAYER_ABILITIES, TEXT_PROCESSING_ORIGINS))
            .build();
}
