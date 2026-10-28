package org.endstone.proxy.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertThrows(IllegalArgumentException.class, () -> CanonicalProtocol.fromConfig("1.26.70"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalProtocol.fromConfig("nonsense"));
    }

    @Test
    void acceptsTheReleaseThatRenumberedTo2193() {
        assertEquals(CanonicalProtocol.V1_26_50, CanonicalProtocol.fromConfig("1.26.50"));
        assertEquals(CanonicalProtocol.V1_26_50, CanonicalProtocol.fromConfig("2193"));
        assertEquals(CanonicalProtocol.V1_26_50, CanonicalProtocol.fromConfig("26.50"));
        assertEquals(2193, CanonicalProtocol.V1_26_50.protocolVersion());
        assertEquals("1.26.50", CanonicalProtocol.V1_26_50.minecraftVersion());
    }

    /**
     * The regression this whole change exists for. 1.26.50 was built against Preview 1.26.50.27,
     * which asks for <b>2192</b>; the stable release asks for <b>2193</b>, and until this test
     * existed nothing in the tree knew the difference. The live proxy turned every real 1.26.50
     * player away for four days with {@code LOGIN_FAILED_SERVER_OLD: client protocol 2193, proxy
     * speaks up to 2192}, which reads to an operator as the proxy being out of date, because it was.
     *
     * <p>2192 is not merely no longer newest — nothing may accept it, because nothing can send it.
     * The preview builds that did have long since updated past it, and leaving it configurable would
     * let an operator pin a number no peer will ever present.
     */
    @Test
    void speaksTheNumberTheStableReleaseActuallySends() {
        assertEquals(2193, CanonicalProtocol.V1_26_50.protocolVersion());
        assertThrows(IllegalArgumentException.class, () -> CanonicalProtocol.fromConfig("2192"));
        assertTrue(CanonicalProtocol.fromProtocolVersion(2192).isEmpty());
        assertEquals(CanonicalProtocol.V1_26_50, CanonicalProtocol.fromProtocolVersion(2193).orElseThrow());
    }

    /**
     * 1.26.50 claims 1.26.51 and stops there, and the stopping point is the point. A hotfix line
     * usually keeps its protocol number and 1.26.51 does — its dump against 1.26.50's changes one
     * line of a README — so covering it is a fact that was read, not an extrapolation. 1.26.45
     * renumbered mid-line after four releases had not, so every further release has to be read the
     * same way before it is claimed here. Claiming one that had in fact renumbered would hand a
     * pinned backend the wrong codec, and a wrong codec is a bad join rather than a clear error;
     * refusing the name is a startup error the operator can act on.
     */
    @Test
    void claimsOnlyTheHotfixItHasSeen() {
        assertEquals("1.26.51", CanonicalProtocol.V1_26_50.newestRelease());
        assertTrue(CanonicalProtocol.V1_26_50.coversRelease("1.26.50"));
        assertTrue(CanonicalProtocol.V1_26_50.coversRelease("1.26.51"));
        assertEquals(CanonicalProtocol.V1_26_50, CanonicalProtocol.fromConfig("1.26.51"));
        assertEquals("1.26.51", CanonicalProtocol.declaredRelease("1.26.51"));
        assertFalse(CanonicalProtocol.V1_26_50.coversRelease("1.26.45"));
        // 1.26.52 has not shipped and has not been read. Extrapolating the hotfix line is the
        // mistake this codec already made once, in the other direction.
        assertFalse(CanonicalProtocol.V1_26_50.coversRelease("1.26.52"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalProtocol.fromConfig("1.26.52"));
        // ...and it must not have been folded into the family below it either.
        assertFalse(CanonicalProtocol.V1_26_45.coversRelease("1.26.50"));
    }

    /**
     * The counterpart to the 2168/2169 test below: 1.26.50 renumbered <em>and</em> changed the
     * format, so it must not join that family. Everything gated on "is this cross-protocol" is a
     * workaround a 1.26.50 client on a 1.26.45 backend genuinely needs, and putting 2193 in the
     * family would switch every one of them off for a pair that really does disagree about the wire.
     */
    @Test
    void theSecondRenumberingDoesNotShareItsWireFormat() {
        assertFalse(CanonicalProtocol.sharesWireFormat(2193, 2169));
        assertFalse(CanonicalProtocol.sharesWireFormat(2169, 2193));
        assertFalse(CanonicalProtocol.sharesWireFormat(2193, 2168));
        assertFalse(CanonicalProtocol.sharesWireFormat(2168, 2193));
        assertTrue(CanonicalProtocol.sharesWireFormat(2193, 2193));
    }

    @Test
    void acceptsTheReleaseThatRenumberedTo2169() {
        // 1.26.45 threw here until it had a codec of its own. It is a distinct protocol, not a
        // member of the 2168 family, so it resolves to its own entry rather than widening 1.26.40's.
        assertEquals(CanonicalProtocol.V1_26_45, CanonicalProtocol.fromConfig("1.26.45"));
        assertEquals(CanonicalProtocol.V1_26_45, CanonicalProtocol.fromConfig("2169"));
        assertEquals(CanonicalProtocol.V1_26_45, CanonicalProtocol.fromConfig("26.45"));
        assertEquals(2169, CanonicalProtocol.V1_26_45.protocolVersion());
        assertEquals("1.26.45", CanonicalProtocol.V1_26_45.minecraftVersion());
    }

    @Test
    void oneNumberOneReleaseNeedsNoReleaseOverride() {
        // The reason 2168 carries a newestRelease at all is that five releases share it. 2169 is one
        // release, so the two answers must agree — if they ever drift, BedrockRelease starts being
        // asked a question about 2169 that it has no business answering.
        assertEquals("1.26.45", CanonicalProtocol.V1_26_45.newestRelease());
        assertEquals(
                CanonicalProtocol.V1_26_45.minecraftVersion(),
                CanonicalProtocol.V1_26_45.newestRelease());
        assertTrue(CanonicalProtocol.V1_26_45.coversRelease("1.26.45"));
        assertFalse(CanonicalProtocol.V1_26_45.coversRelease("1.26.44"));
    }

    @Test
    void theProxyAdvertisesTheNewestRelease() {
        // newest() is what the server list shows and what a client is matched against, so a 2225
        // client is only reachable if this moved with the new codec.
        assertEquals(CanonicalProtocol.V1_26_60, CanonicalProtocol.newest());
        assertEquals(2225, CanonicalProtocol.newest().protocolVersion());
    }

    @Test
    void acceptsTheReleaseThatShippedAs2225() {
        assertEquals(CanonicalProtocol.V1_26_60, CanonicalProtocol.fromConfig("1.26.60"));
        assertEquals(CanonicalProtocol.V1_26_60, CanonicalProtocol.fromConfig("2225"));
        assertEquals(CanonicalProtocol.V1_26_60, CanonicalProtocol.fromConfig("26.60"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalProtocol.fromConfig("1.26.61"));
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

    /**
     * 2168 and 2169 are two numbers for one wire format. Everything the proxy gates on
     * "is this cross-protocol" is a workaround for a backend that genuinely speaks an older format,
     * and applying those to a 1.26.45 client on a 1.26.44 backend cost a disabled blob cache, a
     * clamped chunk radius, a cleared block-registry checksum and two dropped
     * ServerboundDiagnosticsPacket log lines a second, per player.
     */
    @Test
    void theRenumberedProtocolSharesItsWireFormatWithTheOneItReplaced() {
        assertTrue(CanonicalProtocol.sharesWireFormat(2169, 2168));
        assertTrue(CanonicalProtocol.sharesWireFormat(2168, 2169));
        assertTrue(CanonicalProtocol.sharesWireFormat(2169, 2169));
        assertTrue(CanonicalProtocol.sharesWireFormat(2168, 2168));
    }

    @Test
    void aRealVersionGapIsStillARealVersionGap() {
        // These pairs need the workarounds. Widening the family would silently switch them off.
        assertFalse(CanonicalProtocol.sharesWireFormat(2193, 1001));
        assertFalse(CanonicalProtocol.sharesWireFormat(2169, 1001));
        assertFalse(CanonicalProtocol.sharesWireFormat(2168, 1001));
        assertFalse(CanonicalProtocol.sharesWireFormat(1001, 975));
        assertFalse(CanonicalProtocol.sharesWireFormat(975, 898));
        assertTrue(CanonicalProtocol.sharesWireFormat(1001, 1001));
    }

    /**
     * The packets the cross-protocol path drops serverbound all exist on 2168, so a 1.26.45 client
     * must not have them dropped on the way to a 1.26.44 backend. This pins the codec side of that:
     * if the serializer is there, dropping the packet is a bug.
     */
    @Test
    void theDroppedCrossProtocolPacketsExistOnBothSidesOfThisPair() {
        assertNotNull(CanonicalProtocol.V1_26_40.codec().getPacketDefinition(
                org.cloudburstmc.protocol.bedrock.packet.ServerboundDiagnosticsPacket.class),
                "ServerboundDiagnosticsPacket must exist on 2168");
        assertNotNull(CanonicalProtocol.V1_26_45.codec().getPacketDefinition(
                org.cloudburstmc.protocol.bedrock.packet.ServerboundDiagnosticsPacket.class),
                "ServerboundDiagnosticsPacket must exist on 2169");
        assertNotNull(CanonicalProtocol.V1_26_40.codec().getPacketDefinition(
                org.cloudburstmc.protocol.bedrock.packet.CameraAimAssistInstructionPacket.class),
                "CameraAimAssistInstructionPacket must exist on 2168");
        assertNotNull(CanonicalProtocol.V1_26_45.codec().getPacketDefinition(
                org.cloudburstmc.protocol.bedrock.packet.CameraAimAssistInstructionPacket.class),
                "CameraAimAssistInstructionPacket must exist on 2169");
    }
}
