package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

/**
 * Adjacent-version translator for the 1.26.40 (protocol 2168) &harr; 1.26.30 (protocol 1001) step.
 *
 * <p>This is the path that matters in practice: Endstone backends lag a Minecraft release by weeks,
 * so a 1.26.40 client has to be able to play on a 1.26.30 backend.</p>
 *
 * <p>Everything 1.26.40 reshaped on the wire is handled by the codecs themselves — the proxy decodes
 * with one side's codec and re-encodes with the other's, so a field that v2168 added and v1001 has
 * never heard of is simply not written, and vice versa. {@code Bedrock_v2168} overrides 26
 * serializers for exactly this reason, covering StartGame, LevelChunk, SubChunk, MoveEntityDelta,
 * PlayerAuthInput, CraftingData, CreativeContent, PlayerList and the rest; each one reads the 2168
 * shape into the shared packet object that the v1001 serializer then writes in its own shape.</p>
 *
 * <p>No packet was added or removed between 1001 and 2168, so unlike the older translators this one
 * has no id-gap to police — the packet-id table is unchanged and both codecs register the same set.
 * It exists so the registry has an edge to route across, and as the place to put a real translation
 * the moment one is found to be needed.</p>
 *
 * <p><b>Why so little lives here.</b> The type maps do the work that would otherwise be translation
 * code. A wire id is never copied across the hop: the sending codec resolves it to an enum constant
 * and the receiving codec looks that constant up again, so 1.26.40 inserting a particle at 102 and an
 * entity flag at 130 shifts every later id and still round-trips. {@code CrossProtocolTypeMapTest}
 * proves both directions are complete, and {@code CrossProtocolPacketSweepTest} re-encodes every
 * shared packet across the hop.</p>
 *
 * <p>The one mismatch those found was not fixable here. 1.26.40 moved {@code PlayerListPacket}'s
 * add/remove action from the packet onto each entry, and the 1.26.30 reader only ever sets the
 * packet-level one — so a player list relayed to a 1.26.40 client had an action on the packet and
 * null on every entry. That is a model mismatch rather than a routing one, and it also affects
 * player lists the proxy builds itself (which never touch this translator), so it is handled in
 * {@code PlayerListSerializer_v2168} by keeping both halves of the model populated in both
 * directions.</p>
 *
 * <p><b>The known gap.</b> {@code ClientboundUpdateSoundDataPacket} is the one packet whose two
 * shapes do not share a field. v1001 writes a handle plus a single event string; v2168 writes a
 * handle plus seven independent optionals (stop, volume, pitch, fade, seekTo, pause, resume) and no
 * string at all. Decoding one side therefore leaves every field the other side writes unset, so
 * re-encoding across this edge would emit a null event string to a 1.26.30 backend or seven absent
 * optionals to a 1.26.40 client. It is dropped instead: it carries incidental sound state, so losing
 * it costs a sound effect, whereas forwarding it wrong risks a client-side parse error on a packet
 * nobody is watching.</p>
 */
public final class ModernClientTo1001Translator implements PacketTranslator {
    public static final ModernClientTo1001Translator INSTANCE = new ModernClientTo1001Translator();

    private ModernClientTo1001Translator() {
    }

    @Override
    public BedrockPacket translateServerbound(BedrockPacket packet, TranslationContext context) {
        return packet;
    }

    @Override
    public BedrockPacket translateClientbound(BedrockPacket packet, TranslationContext context) {
        if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.ClientboundUpdateSoundDataPacket) {
            return null;
        }
        return packet;
    }

    @Override
    public AvailableCommandsPacket translateCommandTree(AvailableCommandsPacket packet, TranslationContext context) {
        return packet;
    }
}
