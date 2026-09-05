package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

/**
 * Corrections applied to every backend {@link StartGamePacket} before it reaches the client.
 *
 * <p>These are <em>client-behaviour</em> workarounds, not protocol translation, so they apply to
 * same-protocol relaying just as much as to cross-protocol clients. Keeping them here — separate
 * from the cross-protocol normalisation — is deliberate: {@code tickDeathSystems} previously lived
 * behind an {@code isCrossProtocol()} check and was therefore silently inert on a 1001&harr;1001
 * pairing, which made the client disconnect on death with a detail-less "an error has occurred".
 *
 * @param forcedTickDeathSystems     backend reported {@code tickDeathSystems=false}; without the
 *                                   correction the client has no death flow to run and gives up the
 *                                   moment the player dies
 * @param enabledCommands            backend reported commands disabled, which would hide the
 *                                   proxy-injected {@code /server} and {@code /hub} commands
 */
record StartGameClientFixups(
        boolean forcedTickDeathSystems,
        boolean enabledCommands
) {
    /**
     * Bisect switch: relay StartGame exactly as the backend sent it.
     *
     * <p>A client connected straight to the backend dies and respawns correctly, while the same
     * client through the proxy disconnects on death — even though every packet on the death path is
     * verified to re-encode byte-for-byte and nothing is dropped or injected. What the proxy still
     * changes is StartGame and the command tree, so those need to be switchable to find out which
     * (if either) is responsible. Enable with {@code -Dproxy.noStartGameFixups=true}.
     */
    private static final boolean DISABLED = Boolean.getBoolean("proxy.noStartGameFixups");

    static StartGameClientFixups apply(StartGamePacket startGame) {
        return apply(startGame, true);
    }

    static StartGameClientFixups apply(StartGamePacket startGame, boolean proxyCommandsEnabled) {
        if (DISABLED) {
            return new StartGameClientFixups(false, false);
        }
        // tickDeathSystems is deliberately NOT corrected here. BDS 1.26.x reports false, and a
        // client connected directly to that same backend dies and respawns correctly with false —
        // so false is not the death-disconnect cause, and overriding it only makes the proxy
        // diverge from a configuration known to work. The 1.26.20-era note claiming a client
        // disconnects on death when it is false was not reproducible on protocol 1001.
        boolean forcedTickDeathSystems = false;

        // defaultPlayerPermission is deliberately NOT raised. Forcing OPERATOR here made every
        // player's client show them as an operator — the op badge, the operator command set in
        // autocomplete — while the backend still treated them as the member they are. It was never
        // needed for the injected commands either: those are advertised at CommandPermission.ANY,
        // which any permission level can see. Per-player operator status is synchronized separately
        // from the backend's UpdateAbilities command permission.

        boolean enabledCommands = proxyCommandsEnabled && !startGame.isCommandsEnabled();
        if (enabledCommands) {
            startGame.setCommandsEnabled(true);
        }

        return new StartGameClientFixups(forcedTickDeathSystems, enabledCommands);
    }
}
