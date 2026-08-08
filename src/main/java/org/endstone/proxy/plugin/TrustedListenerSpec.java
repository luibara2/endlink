package org.endstone.proxy.plugin;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;

import java.net.InetSocketAddress;

/**
 * An extra Bedrock listener an addon needs the proxy to bind for it, on loopback, with the checks
 * that only make sense for the public port turned off.
 *
 * <p>The proxy binds and owns it; the addon only describes it. Everything here exists because a
 * translator connecting into the proxy is not a player and must not be treated as one.</p>
 *
 * @param address         where to bind. <b>Rejected unless it is a loopback address</b> — the whole
 *                        safety argument for {@code loginSecret} and the relaxed checks below is that
 *                        nothing off this machine can reach the port
 * @param advertisedCodec what this port claims to be when pinged. A translator generally speaks one
 *                        Bedrock version and will not connect to a port advertising another
 * @param loginSecret     required in every self-signed login arriving here. Self-signed logins are
 *                        accepted on this port because a translator has no Xbox account to sign with;
 *                        the secret is what narrows "any local process may claim any identity" down
 *                        to "the addon the proxy itself started". Blank disables the check and is a
 *                        bad idea
 * @param namePrefix      prepended to the display name of everyone arriving here, so players from an
 *                        addon are distinguishable in chat and on the player list. Applied after
 *                        identity is derived, so changing it does not move anyone's permissions or
 *                        backend data. Empty for none
 */
public record TrustedListenerSpec(
        InetSocketAddress address,
        BedrockCodec advertisedCodec,
        String loginSecret,
        String namePrefix
) {
    public TrustedListenerSpec {
        if (address == null) {
            throw new IllegalArgumentException("address cannot be null");
        }
        if (address.getAddress() == null || !address.getAddress().isLoopbackAddress()) {
            throw new IllegalArgumentException(
                    "a trusted listener must bind a loopback address, not " + address
                            + ". It accepts self-signed logins, so exposing it to the network would let "
                            + "anyone join as anyone.");
        }
        if (advertisedCodec == null) {
            throw new IllegalArgumentException("advertisedCodec cannot be null");
        }
        loginSecret = loginSecret == null ? "" : loginSecret;
        namePrefix = namePrefix == null ? "" : namePrefix;
    }
}
