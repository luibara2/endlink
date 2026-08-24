package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.BedrockCodecHelper_v2168;

/**
 * The Minecraft release a peer actually runs, as distinct from the protocol number it negotiates.
 *
 * <p>Normally those two say the same thing and only the number is worth keeping. Protocol 2168 is
 * the exception: Mojang shipped 1.26.40, 1.26.41, 1.26.42, 1.26.43 and 1.26.44 under it, and
 * <em>changed the wire format at 1.26.44 without changing the number</em>. A 1.26.40 client and a
 * 1.26.44 client both ask for 2168, both are accepted, and then they disagree about the shape of a
 * {@code RemoveScore} entry — see
 * {@link org.cloudburstmc.protocol.bedrock.codec.v2168.BedrockCodecHelper_v2168#isRemoveScoreKeyedConstant()}.
 *
 * <p>Everyone who speaks this protocol had to add the same gate: gophertunnel reads the client's
 * {@code GameVersion} out of its login and picks a packet pool from it (Sandertv/gophertunnel#505),
 * and Endstone reimplemented {@code SemVersion::fromString} to rewrite {@code SetScorePacket} for
 * pre-1.26.44 clients (EndstoneMC/endstone c9a4d75). This is the proxy's copy of that gate, and it
 * is needed on <em>both</em> sides, because a proxy is the one participant that holds two peers at
 * once and they are routinely on different releases.
 *
 * <p>The version string is whatever the peer supplied — {@code GameVersion} from a client's login
 * payload, or the version field of a backend's RakNet pong. Both are self-reported and neither is
 * validated anywhere, so every method here treats an unreadable value as "no opinion" rather than
 * as an error.
 */
public final class BedrockRelease {

    /** Where {@link #parse} stops counting. Every real component is orders of magnitude below it. */
    static final int MAX_COMPONENT = 1_000_000;

    private BedrockRelease() {
    }

    /**
     * Whether a peer running {@code minecraftVersion} writes and expects the constant {@code true}
     * in a {@code RemoveScore} entry — that is, whether it is 1.26.44 or newer.
     *
     * <p>Unknown, blank and unparseable versions answer {@code true}. The alternative default is
     * worse in exactly the case that matters: this proxy speaks only the current release, so an
     * unrecognised peer is far more likely to be ahead of this table than behind it, and guessing
     * "old" would corrupt every removal for a peer that is simply newer than this code. Anything
     * genuinely old enough to want the other shape is old enough to say so.
     */
    public static boolean carriesRemoveScoreKeyedConstant(String minecraftVersion) {
        return compare(minecraftVersion, 1, 26, 44) >= 0;
    }

    /**
     * {@code minecraftVersion} against {@code major.minor.patch}: negative if it is older, positive
     * if it is newer, zero if they match on those three components or the version is unreadable.
     *
     * <p>A fourth component is deliberately ignored. Mojang's build numbers are not ordered against
     * anything useful — 1.26.40.31 and 1.26.44.3 are consecutive releases — and no wire difference
     * this proxy knows about has ever been keyed to one.
     */
    public static int compare(String minecraftVersion, int major, int minor, int patch) {
        int[] parsed = parse(minecraftVersion);
        if (parsed == null) {
            return 0;
        }
        int result = Integer.compare(parsed[0], major);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(parsed[1], minor);
        if (result != 0) {
            return result;
        }
        return Integer.compare(parsed[2], patch);
    }

    /**
     * {@code major}, {@code minor} and {@code patch} from a version string, or null if it does not
     * start with three dot-separated numbers.
     *
     * <p>Tolerant on purpose. Real values seen in this position include {@code "1.26.44"},
     * {@code "1.26.44.3"}, {@code "1.21.130.28"} and, from clients whose build carries a suffix,
     * things like {@code "1.26.44.1-beta"}. All four are readable; a name, an empty string or a
     * two-component version is not, and comes back null.
     */
    public static int[] parse(String minecraftVersion) {
        if (minecraftVersion == null) {
            return null;
        }
        int[] parts = new int[3];
        int index = 0;
        for (int part = 0; part < 3; part++) {
            if (part > 0) {
                if (index >= minecraftVersion.length() || minecraftVersion.charAt(index) != '.') {
                    return null;
                }
                index++;
            }
            int start = index;
            // Saturating rather than Integer.parseInt on a split: a version string is
            // attacker-supplied (it is a field of a login payload) and a long run of digits should
            // not become an overflowing parse or a NumberFormatException on the hot path. Saturating
            // is safe here because the cap is far above any real component and this value is only
            // ever compared, so "absurdly large" and "larger still" mean the same thing.
            int value = 0;
            while (index < minecraftVersion.length()
                    && minecraftVersion.charAt(index) >= '0'
                    && minecraftVersion.charAt(index) <= '9') {
                if (value < MAX_COMPONENT) {
                    value = Math.min(MAX_COMPONENT, value * 10 + (minecraftVersion.charAt(index) - '0'));
                }
                index++;
            }
            if (index == start) {
                return null;
            }
            parts[part] = value;
        }
        return parts;
    }

    /** {@code minecraftVersion} if it says anything, otherwise {@code "unknown"}. */
    public static String describe(String minecraftVersion) {
        return minecraftVersion == null || minecraftVersion.isBlank() ? "unknown" : minecraftVersion;
    }

    /**
     * Tells {@code session}'s codec helper which release the peer on the other end is running, so
     * the serializers that differ within one protocol number pick the right shape.
     *
     * <p><strong>Call this after {@code setCodec}, every time.</strong> {@code setCodec} builds a
     * fresh helper and throws away the old one along with anything set on it, so a call made before
     * it is simply lost — silently, and in a way that only shows up as corrupt packets on a peer
     * running a release nobody tested against.
     *
     * <p>Does nothing for any protocol but 2168, which is the only one where the release matters.
     */
    public static void applyTo(BedrockSession session, String minecraftVersion) {
        if (session == null) {
            return;
        }
        BedrockCodecHelper helper = session.getPeer().getCodecHelper();
        if (helper instanceof BedrockCodecHelper_v2168 v2168) {
            v2168.setRemoveScoreKeyedConstant(carriesRemoveScoreKeyedConstant(minecraftVersion));
        }
    }
}
