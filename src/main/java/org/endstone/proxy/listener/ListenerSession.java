package org.endstone.proxy.listener;

import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.endstone.proxy.backend.ProxyConnection;
import org.endstone.proxy.session.ProxySessionProfile;

import java.util.function.Consumer;

public final class ListenerSession extends BedrockServerSession {
    private final Consumer<ListenerSession> closeListener;
    private BedrockCodec clientCodec;
    private ProxySessionProfile sessionProfile;
    private ProxyConnection proxyConnection;
    private BedrockPacketWrapper currentInboundPacket;
    private boolean throttled = true;

    public ListenerSession(BedrockPeer peer, int subClientId, Consumer<ListenerSession> closeListener) {
        super(peer, subClientId);
        this.closeListener = closeListener;
    }

    public ProxySessionProfile sessionProfile() {
        return sessionProfile;
    }

    public void setSessionProfile(ProxySessionProfile sessionProfile) {
        this.sessionProfile = sessionProfile;
    }

    public ProxyConnection proxyConnection() {
        return proxyConnection;
    }

    public void setProxyConnection(ProxyConnection proxyConnection) {
        this.proxyConnection = proxyConnection;
    }

    public BedrockPacketWrapper currentInboundPacket() {
        return currentInboundPacket;
    }

    /**
     * Whether this session claimed a slot from the per-address connection throttle, and so must return
     * one when it closes. False for bridge sessions, which never took one — releasing a slot that
     * was not claimed would hand 127.0.0.1 a growing free allowance.
     */
    public boolean isThrottled() {
        return throttled;
    }

    public void setThrottled(boolean throttled) {
        this.throttled = throttled;
    }

    public BedrockCodec clientCodec() {
        return clientCodec;
    }

    public void setClientCodec(BedrockCodec clientCodec) {
        this.clientCodec = clientCodec;
    }

    @Override
    protected void onPacket(BedrockPacketWrapper wrapper) {
        currentInboundPacket = wrapper;
        try {
            super.onPacket(wrapper);
        } finally {
            currentInboundPacket = null;
        }
    }

    @Override
    protected void onClose() {
        if (proxyConnection == null) {
            System.out.printf("Client %s disconnected before login: %s.%n", getSocketAddress(), getDisconnectReason());
        } else {
            System.out.printf(
                    "Player %s (XUID %s) left the proxy from %s (backend %s): %s.%n",
                    proxyConnection.clientLogin().authData().displayName(),
                    proxyConnection.clientLogin().authData().xuid(),
                    // Not getSocketAddress(): a bridged player's socket is the bridge's loopback one, so this line reported 127.0.0.1 while the join line reported the real
                    // address — the same player appearing to arrive and leave from two places.
                    proxyConnection.clientAddress(),
                    proxyConnection.backendName(),
                    getDisconnectReason()
            );
        }
        super.onClose();
        if (proxyConnection != null) {
            proxyConnection.closeBackend(getDisconnectReason());
        }
        closeListener.accept(this);
    }
}
