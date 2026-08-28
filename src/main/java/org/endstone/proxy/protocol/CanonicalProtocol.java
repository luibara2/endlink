package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v898.Bedrock_v898;
import org.cloudburstmc.protocol.bedrock.codec.v924.Bedrock_v924;
import org.cloudburstmc.protocol.bedrock.codec.v944.Bedrock_v944;
import org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v2169.Bedrock_v2169;

import java.util.Optional;

public enum CanonicalProtocol {
    V1_21_130(Bedrock_v898.CODEC),
    V1_26_0(Bedrock_v924.CODEC),
    V1_26_10(Bedrock_v944.CODEC),
    V1_26_20(Bedrock_v975.CODEC),
    // 1.26.30, .32, .33 and .35 are all protocol 1001 and all decode on this one codec.
    V1_26_30(Bedrock_v1001.CODEC, "1.26.35"),
    // Mojang renumbered at 1.26.40: 1001 -> 2168, not the ~1010 the earlier steps would suggest.
    // 1.26.40 through 1.26.44 then all shipped under 2168 — and 1.26.44 changed a packet layout
    // anyway, which is why naming the release matters and the protocol number is not enough. See
    // BedrockRelease.
    V1_26_40(Bedrock_v2168.CODEC, "1.26.44"),
    // 1.26.45 is a hotfix that renumbered to 2169 and dropped the RemoveScore constant 1.26.44 added.
    // One codec, one release: the protocol number identifies the wire format again, so this entry
    // needs no newest-release override and BedrockRelease has nothing to decide for it.
    V1_26_45(Bedrock_v2169.CODEC);

    private final BedrockCodec codec;

    /**
     * The newest Minecraft release this codec speaks, inclusive. Equal to the codec's own version for
     * every protocol Mojang numbered once; larger for the two they did not.
     *
     * <p>This exists so a config can name the release the operator is <em>actually</em> running. Before
     * it, {@code backend.hub.protocol=1.26.44} threw "Unsupported backend protocol" at startup and the
     * only accepted spelling was {@code 1.26.40} — a value that then had to be read back as the
     * backend's release, and was wrong for every backend that had moved on.
     */
    private final String newestRelease;

    CanonicalProtocol(BedrockCodec codec) {
        this(codec, codec.getMinecraftVersion());
    }

    CanonicalProtocol(BedrockCodec codec, String newestRelease) {
        this.codec = codec;
        this.newestRelease = newestRelease;
    }

    /** The newest Minecraft release this codec speaks, inclusive. */
    public String newestRelease() {
        return newestRelease;
    }

    /** Whether {@code minecraftVersion} is one of the releases this codec speaks. */
    public boolean coversRelease(String minecraftVersion) {
        int[] parsed = BedrockRelease.parse(minecraftVersion);
        if (parsed == null) {
            return false;
        }
        int[] oldest = BedrockRelease.parse(minecraftVersion());
        int[] newest = BedrockRelease.parse(newestRelease);
        return oldest != null
                && newest != null
                && BedrockRelease.compare(minecraftVersion, oldest[0], oldest[1], oldest[2]) >= 0
                && BedrockRelease.compare(minecraftVersion, newest[0], newest[1], newest[2]) <= 0;
    }

    public BedrockCodec codec() {
        return codec;
    }

    public int protocolVersion() {
        return codec.getProtocolVersion();
    }

    public String minecraftVersion() {
        return codec.getMinecraftVersion();
    }

    /**
     * The newest client version the proxy speaks. This is what the server list advertises, so
     * anything user-facing that names a version should derive it from here rather than hardcode one.
     */
    public static CanonicalProtocol newest() {
        CanonicalProtocol[] values = values();
        return values[values.length - 1];
    }

    /**
     * What a configured {@code protocol} value names.
     *
     * @param protocol the codec to speak
     * @param release  the Minecraft release the operator named, or null when they named a bare
     *                 protocol number. Null is not "1.26.40" — it means the config did not say, and a
     *                 protocol number cannot say, because one number covers several releases that do
     *                 not all share a wire format.
     */
    public record Declared(CanonicalProtocol protocol, String release) {
    }

    public static CanonicalProtocol fromConfig(String value) {
        Declared declared = declare(value);
        return declared == null ? null : declared.protocol();
    }

    /**
     * The Minecraft release a configured {@code protocol} value names, or null for {@code auto}, a
     * blank value, or a bare protocol number.
     *
     * <p>Kept separate from {@link #fromConfig} because the two answer different questions and only
     * one of them has a right to a default. Reading the codec's own {@code minecraftVersion()} back
     * out of {@link #fromConfig}'s result looks like the release and is not: for protocol 2168 it is
     * "1.26.40" whatever the backend actually runs, which silently corrupts every scoreboard removal
     * from a 1.26.44 backend that happens to be pinned.
     */
    public static String declaredRelease(String value) {
        Declared declared = declare(value);
        return declared == null ? null : declared.release();
    }

    public static Declared declare(String value) {
        if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value.trim())) {
            return null;
        }
        String normalized = value.trim();
        for (CanonicalProtocol protocol : values()) {
            if (Integer.toString(protocol.protocolVersion()).equals(normalized)) {
                return new Declared(protocol, null);
            }
        }
        // "26.40" has always been accepted alongside "1.26.40"; normalise before matching so the
        // release carried forward is a full version string either way.
        String release = normalized.startsWith("1.") ? normalized : "1." + normalized;
        for (CanonicalProtocol protocol : values()) {
            if (protocol.coversRelease(release)) {
                return new Declared(protocol, release);
            }
        }
        throw new IllegalArgumentException("Unsupported backend protocol: " + value);
    }

    /**
     * Whether two protocol numbers describe the same wire format, and therefore need no translation
     * between them.
     *
     * <p>Normally this is just equality. 2168 and 2169 are the exception: 1.26.45 renumbered the
     * protocol for a single field — the {@code RemoveScore} constant — and each side's own codec
     * helper already writes its own shape for that one field when a packet is re-encoded. Every
     * other packet, the block palette, the chunk format and the entity data layout are identical.
     *
     * <p><b>This exists because "the numbers differ" is not the same question as "the versions
     * differ", and the proxy has a long history of conflating them.</b> Everything gated on
     * {@code isCrossProtocol()} is a workaround for a backend that genuinely speaks an older
     * format: the blob cache is turned off, the initial chunk radius is clamped, the StartGame
     * block-registry checksum is cleared, and a handful of serverbound packets the older codecs
     * never had are dropped. Applied to a 1.26.45 client on a 1.26.44 backend, every one of those
     * is wrong — the packets exist on both ends, the palettes match, and the cache would have
     * worked — and the dropped {@code ServerboundDiagnosticsPacket} alone produced two log lines a
     * second, for every player, forever.
     *
     * <p>So a new codec that is wire-compatible with its neighbour must be named here as well as
     * registered. Adding one without this makes it join, and then quietly degrades it.
     */
    public static boolean sharesWireFormat(int protocolVersion, int otherProtocolVersion) {
        return protocolVersion == otherProtocolVersion
                || (isRemoveScoreOnlyFamily(protocolVersion) && isRemoveScoreOnlyFamily(otherProtocolVersion));
    }

    /** 1.26.40 through 1.26.44 (2168) and 1.26.45 (2169): one wire format, one differing field. */
    private static boolean isRemoveScoreOnlyFamily(int protocolVersion) {
        return protocolVersion == 2168 || protocolVersion == 2169;
    }

    public static Optional<CanonicalProtocol> fromProtocolVersion(int protocolVersion) {
        for (CanonicalProtocol protocol : values()) {
            if (protocol.protocolVersion() == protocolVersion) {
                return Optional.of(protocol);
            }
        }
        return Optional.empty();
    }
}
