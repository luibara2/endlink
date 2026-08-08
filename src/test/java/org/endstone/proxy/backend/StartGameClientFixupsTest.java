package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.data.PlayerPermission;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * These fixups are client-behaviour workarounds, so they must apply to every protocol pairing.
 * {@code tickDeathSystems} regressed once by sitting behind a cross-protocol check, which left a
 * same-protocol client disconnecting on death; these tests pin the corrections to the packet
 * contents alone so no such gate can be reintroduced unnoticed.
 */
class StartGameClientFixupsTest {

    private static StartGamePacket backendStartGame() {
        StartGamePacket startGame = new StartGamePacket();
        // What BDS 1.26.x actually reports, as observed in the relay logs.
        startGame.setTickDeathSystemsEnabled(false);
        startGame.setCommandsEnabled(false);
        startGame.setDefaultPlayerPermission(PlayerPermission.MEMBER);
        return startGame;
    }

    @Test
    void leavesTickDeathSystemsAloneBecauseTheBackendsValueIsKnownGood() {
        StartGamePacket startGame = backendStartGame();

        StartGameClientFixups fixups = StartGameClientFixups.apply(startGame);

        // A client connected directly to the same backend dies and respawns correctly with false,
        // so the proxy must not diverge from what that backend reports.
        assertFalse(startGame.isTickDeathSystemsEnabled(), "must relay the backend's value unchanged");
        assertFalse(fixups.forcedTickDeathSystems());
    }

    @Test
    void enablesCommandsSoInjectedProxyCommandsAreVisible() {
        StartGamePacket startGame = backendStartGame();

        StartGameClientFixups fixups = StartGameClientFixups.apply(startGame);

        assertTrue(startGame.isCommandsEnabled());
        assertTrue(fixups.enabledCommands());
    }

    @Test
    void relaysTheBackendsDefaultPlayerPermissionUnchanged() {
        StartGamePacket startGame = backendStartGame();

        StartGameClientFixups.apply(startGame);

        // Raising this to OPERATOR gave every joining player an op badge and the operator command
        // set in autocomplete while the backend still treated them as a member. Nobody gained a
        // permission, but everybody looked like they had.
        assertEquals(PlayerPermission.MEMBER, startGame.getDefaultPlayerPermission());
    }

    @Test
    void leavesAnOperatorBackendPermissionAloneToo() {
        StartGamePacket startGame = backendStartGame();
        startGame.setDefaultPlayerPermission(PlayerPermission.OPERATOR);

        StartGameClientFixups.apply(startGame);

        assertEquals(PlayerPermission.OPERATOR, startGame.getDefaultPlayerPermission());
    }

    @Test
    void reportsNothingChangedWhenTheBackendAlreadyAgrees() {
        StartGamePacket startGame = new StartGamePacket();
        startGame.setCommandsEnabled(true);

        StartGameClientFixups fixups = StartGameClientFixups.apply(startGame);

        assertFalse(fixups.forcedTickDeathSystems());
        assertFalse(fixups.enabledCommands());
        // Still correct afterwards - the fixups must be idempotent.
        assertTrue(startGame.isCommandsEnabled());
    }

    @Test
    void isIdempotentAcrossRepeatedBackendSwitches() {
        StartGamePacket startGame = backendStartGame();

        StartGameClientFixups.apply(startGame);
        StartGameClientFixups second = StartGameClientFixups.apply(startGame);

        assertFalse(second.forcedTickDeathSystems());
        assertFalse(second.enabledCommands());
        assertTrue(startGame.isCommandsEnabled());
        assertEquals(PlayerPermission.MEMBER, startGame.getDefaultPlayerPermission());
    }
}
