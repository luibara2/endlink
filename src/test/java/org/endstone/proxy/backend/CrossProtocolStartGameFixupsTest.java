package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A 1.26.40 client on a 1.26.30 backend disconnected with "blocks between client and server do not
 * match" before it rendered a chunk. StartGame's block type registry checksum is computed over the
 * server's palette and compared against the client's own, so two different Minecraft versions can
 * never agree — the join is rejected on a check that cannot pass by construction.
 */
class CrossProtocolStartGameFixupsTest {

    private static StartGamePacket backendStartGame() {
        StartGamePacket startGame = new StartGamePacket();
        startGame.setBlockRegistryChecksum(0x1122334455667788L);
        startGame.setBlockNetworkIdsHashed(true);
        return startGame;
    }

    @Test
    void clearsTheBlockChecksumWhenTheVersionsDiffer() {
        StartGamePacket startGame = backendStartGame();

        CrossProtocolStartGameFixups fixups = CrossProtocolStartGameFixups.apply(startGame, true);

        assertEquals(0L, startGame.getBlockRegistryChecksum(),
                "zero is the documented opt-out; anything else is compared and cannot match");
        assertEquals(0x1122334455667788L, fixups.clearedBlockRegistryChecksum(),
                "the original is reported so the log can say what was dropped");
    }

    @Test
    void leavesTheChecksumAloneAtMatchingVersions() {
        StartGamePacket startGame = backendStartGame();

        CrossProtocolStartGameFixups fixups = CrossProtocolStartGameFixups.apply(startGame, false);

        // Same-version pairings keep a real consistency check. Clearing it everywhere would hide a
        // genuinely corrupt palette, and 26.40 -> 26.40 already works without this.
        assertEquals(0x1122334455667788L, startGame.getBlockRegistryChecksum());
        assertEquals(0L, fixups.clearedBlockRegistryChecksum());
        assertFalse(fixups.indexBasedBlockIds());
    }

    @Test
    void flagsIndexBasedBlockIdsBecauseNoStartGameFixCanReconcileThem() {
        StartGamePacket startGame = backendStartGame();
        startGame.setBlockNetworkIdsHashed(false);

        CrossProtocolStartGameFixups fixups = CrossProtocolStartGameFixups.apply(startGame, true);

        // Hashed ids hash the block state NBT, so an unchanged block keeps its id across versions.
        // Palette indices do not, and silencing the checksum would then leave the player in a world
        // of wrong blocks rather than an honest disconnect — worth a loud warning.
        assertTrue(fixups.indexBasedBlockIds());
    }

    @Test
    void isIdempotentSoARelayedStartGameIsNotReportedTwice() {
        StartGamePacket startGame = backendStartGame();

        CrossProtocolStartGameFixups.apply(startGame, true);
        CrossProtocolStartGameFixups second = CrossProtocolStartGameFixups.apply(startGame, true);

        assertEquals(0L, second.clearedBlockRegistryChecksum());
    }
}
