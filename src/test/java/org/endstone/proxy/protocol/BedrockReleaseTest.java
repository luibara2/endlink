package org.endstone.proxy.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 1.26.44 gate, which is the only thing that tells two peers on protocol 2168 apart.
 *
 * @see BedrockRelease
 */
class BedrockReleaseTest {

    @Test
    void readsTheThreeComponentsAndIgnoresTheBuildNumber() {
        assertArrayEquals(new int[]{1, 26, 44}, BedrockRelease.parse("1.26.44"));
        assertArrayEquals(new int[]{1, 26, 44}, BedrockRelease.parse("1.26.44.3"));
        assertArrayEquals(new int[]{1, 26, 40}, BedrockRelease.parse("1.26.40.31"));
        assertArrayEquals(new int[]{1, 21, 130}, BedrockRelease.parse("1.21.130.28"));
    }

    @Test
    void readsAVersionWithASuffixOnItsLastComponent() {
        assertArrayEquals(new int[]{1, 26, 44}, BedrockRelease.parse("1.26.44-beta"));
        assertArrayEquals(new int[]{1, 26, 50}, BedrockRelease.parse("1.26.50.26-preview"));
    }

    @Test
    void refusesAnythingThatIsNotThreeNumbers() {
        assertNull(BedrockRelease.parse(null));
        assertNull(BedrockRelease.parse(""));
        assertNull(BedrockRelease.parse("1.26"));
        assertNull(BedrockRelease.parse("1.26."));
        assertNull(BedrockRelease.parse("1..44"));
        assertNull(BedrockRelease.parse("not a version"));
    }

    /**
     * A version string reaches this class straight out of a login payload, so it is attacker
     * controlled. A pathological one must come back with an answer rather than an exception.
     */
    @Test
    void survivesAnAbsurdVersionString() {
        assertArrayEquals(
                new int[]{1, 26, BedrockRelease.MAX_COMPONENT},
                BedrockRelease.parse("1.26." + "9".repeat(400)));
        assertTrue(BedrockRelease.carriesRemoveScoreKeyedConstant("1.26." + "9".repeat(400)));
    }

    @Test
    void gatesTheKeyedConstantAt_1_26_44() {
        assertFalse(BedrockRelease.carriesRemoveScoreKeyedConstant("1.26.40"));
        assertFalse(BedrockRelease.carriesRemoveScoreKeyedConstant("1.26.40.31"));
        assertFalse(BedrockRelease.carriesRemoveScoreKeyedConstant("1.26.41"));
        assertFalse(BedrockRelease.carriesRemoveScoreKeyedConstant("1.26.42.1"));
        assertFalse(BedrockRelease.carriesRemoveScoreKeyedConstant("1.26.43.1"));

        assertTrue(BedrockRelease.carriesRemoveScoreKeyedConstant("1.26.44"));
        assertTrue(BedrockRelease.carriesRemoveScoreKeyedConstant("1.26.44.3"));
    }

    /**
     * The whole point of the gate: everything on protocol 2168 is 1.26.40 to 1.26.44, and the two
     * ends of that range answer differently. A test that only checked one of them would pass with
     * the gate deleted.
     */
    @Test
    void theTwoEndsOfProtocol2168Disagree() {
        assertFalse(BedrockRelease.carriesRemoveScoreKeyedConstant("1.26.40"));
        assertTrue(BedrockRelease.carriesRemoveScoreKeyedConstant("1.26.44"));
    }

    /**
     * An unreadable version gets the current release's shape, not the oldest one. This proxy speaks
     * only the newest protocol it knows, so a peer it cannot identify is far likelier to be ahead of
     * this code than behind it — and guessing "old" would corrupt every removal for that peer.
     */
    @Test
    void assumesTheCurrentReleaseWhenThePeerDoesNotSay() {
        assertTrue(BedrockRelease.carriesRemoveScoreKeyedConstant(null));
        assertTrue(BedrockRelease.carriesRemoveScoreKeyedConstant(""));
        assertTrue(BedrockRelease.carriesRemoveScoreKeyedConstant("who knows"));
        assertTrue(BedrockRelease.carriesRemoveScoreKeyedConstant("1.27.0"));
    }

    @Test
    void comparesAcrossEveryComponent() {
        assertTrue(BedrockRelease.compare("2.0.0", 1, 26, 44) > 0);
        assertTrue(BedrockRelease.compare("1.27.0", 1, 26, 44) > 0);
        assertTrue(BedrockRelease.compare("1.26.45", 1, 26, 44) > 0);
        assertEquals(0, BedrockRelease.compare("1.26.44.99", 1, 26, 44));
        assertTrue(BedrockRelease.compare("1.26.30", 1, 26, 44) < 0);
        assertTrue(BedrockRelease.compare("1.21.130", 1, 26, 44) < 0);
        assertTrue(BedrockRelease.compare("0.99.99", 1, 26, 44) < 0);
    }

    @Test
    void describesAMissingVersionRatherThanPrintingNull() {
        assertEquals("unknown", BedrockRelease.describe(null));
        assertEquals("unknown", BedrockRelease.describe("  "));
        assertEquals("1.26.44.3", BedrockRelease.describe("1.26.44.3"));
    }

    /** Nothing may throw on a session that is already gone by the time the login is processed. */
    @Test
    void toleratesAnAbsentSession() {
        BedrockRelease.applyTo(null, "1.26.44");
    }
}
