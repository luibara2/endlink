package org.endstone.proxy.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A configured protocol names a codec; it does not always name a release.
 *
 * <p>Two protocol numbers cover more than one Minecraft release — 1001 covers 1.26.30 to 1.26.35 and
 * 2168 covers 1.26.40 to 1.26.44 — and 1.26.44 changed a packet layout without changing the number.
 * So "which codec" and "which release" became separate questions, and the codec's own
 * {@code minecraftVersion()} is only an answer to the first.
 */
class CanonicalProtocolReleaseTest {

    @Test
    void acceptsEveryReleaseInAProtocolFamily() {
        for (String release : new String[]{"1.26.40", "1.26.41", "1.26.42", "1.26.43", "1.26.44"}) {
            assertEquals(CanonicalProtocol.V1_26_40, CanonicalProtocol.fromConfig(release), release);
        }
        for (String release : new String[]{"1.26.30", "1.26.32", "1.26.33", "1.26.35"}) {
            assertEquals(CanonicalProtocol.V1_26_30, CanonicalProtocol.fromConfig(release), release);
        }
    }

    /**
     * The regression that mattered: pinning the release actually being run used to throw at startup,
     * so the only spelling an operator could use was the one that then read back as a wrong release.
     */
    @Test
    void pinningTheCurrentReleaseNoLongerFailsToStart() {
        assertEquals(CanonicalProtocol.V1_26_40, CanonicalProtocol.fromConfig("1.26.44"));
        assertEquals("1.26.44", CanonicalProtocol.declaredRelease("1.26.44"));
    }

    @Test
    void carriesTheReleaseTheOperatorNamed() {
        assertEquals("1.26.40", CanonicalProtocol.declaredRelease("1.26.40"));
        assertEquals("1.26.44", CanonicalProtocol.declaredRelease("1.26.44"));
        assertEquals("1.26.44", CanonicalProtocol.declaredRelease("  1.26.44  "));
        assertEquals("1.26.44", CanonicalProtocol.declaredRelease("26.44"));
    }

    /** A bare protocol number cannot name a release, and must not be made to look as if it did. */
    @Test
    void aBareProtocolNumberNamesNoRelease() {
        assertEquals(CanonicalProtocol.V1_26_40, CanonicalProtocol.fromConfig("2168"));
        assertNull(CanonicalProtocol.declaredRelease("2168"));
    }

    @Test
    void autoAndBlankNameNeitherCodecNorRelease() {
        for (String value : new String[]{null, "", "   ", "auto", "AUTO"}) {
            assertNull(CanonicalProtocol.fromConfig(value));
            assertNull(CanonicalProtocol.declaredRelease(value));
        }
    }

    @Test
    void stillRefusesAReleaseNoCodecSpeaks() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalProtocol.fromConfig("1.26.50"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalProtocol.fromConfig("1.26.45"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalProtocol.fromConfig("nonsense"));
    }

    @Test
    void knowsHowFarEachProtocolFamilyReaches() {
        assertEquals("1.26.44", CanonicalProtocol.V1_26_40.newestRelease());
        assertEquals("1.26.35", CanonicalProtocol.V1_26_30.newestRelease());
        // A protocol Mojang numbered once reaches exactly its own release.
        assertEquals("1.26.20", CanonicalProtocol.V1_26_20.newestRelease());
        assertEquals(
                CanonicalProtocol.V1_26_20.minecraftVersion(),
                CanonicalProtocol.V1_26_20.newestRelease());
    }

    @Test
    void coversOnlyItsOwnFamily() {
        assertTrue(CanonicalProtocol.V1_26_40.coversRelease("1.26.44"));
        assertFalse(CanonicalProtocol.V1_26_40.coversRelease("1.26.35"));
        assertFalse(CanonicalProtocol.V1_26_40.coversRelease("1.26.45"));
        assertFalse(CanonicalProtocol.V1_26_30.coversRelease("1.26.40"));
        assertFalse(CanonicalProtocol.V1_26_40.coversRelease("not a version"));
        assertFalse(CanonicalProtocol.V1_26_40.coversRelease(null));
    }

    /**
     * The join between this class and the wire: what the operator writes has to reach the gate that
     * decides the packet shape, and 1.26.40 and 1.26.44 have to reach it differently.
     */
    @Test
    void theDeclaredReleaseDrivesTheRemoveScoreShape() {
        assertFalse(BedrockRelease.carriesRemoveScoreKeyedConstant(
                CanonicalProtocol.declaredRelease("1.26.40")));
        assertTrue(BedrockRelease.carriesRemoveScoreKeyedConstant(
                CanonicalProtocol.declaredRelease("1.26.44")));
        // ...and a bare protocol number falls through to "not stated", which means current release.
        assertTrue(BedrockRelease.carriesRemoveScoreKeyedConstant(
                CanonicalProtocol.declaredRelease("2168")));
    }
}
