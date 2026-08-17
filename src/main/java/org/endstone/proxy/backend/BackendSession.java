package org.endstone.proxy.backend;

import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;

public final class BackendSession extends BedrockClientSession {
    private ProxyConnection connection;
    private boolean disconnectClientOnClose = true;
    private boolean dropSubChunkRequests;
    private BedrockPacketWrapper currentInboundPacket;

    public BackendSession(BedrockPeer peer, int subClientId) {
        super(peer, subClientId);
    }

    public ProxyConnection connection() {
        return connection;
    }

    public void setConnection(ProxyConnection connection) {
        this.connection = connection;
    }

    public void setDisconnectClientOnClose(boolean disconnectClientOnClose) {
        this.disconnectClientOnClose = disconnectClientOnClose;
    }

    /** @see org.endstone.proxy.config.BackendConfig#dropSubChunkRequests() */
    public boolean dropSubChunkRequests() {
        return dropSubChunkRequests;
    }

    public void setDropSubChunkRequests(boolean dropSubChunkRequests) {
        this.dropSubChunkRequests = dropSubChunkRequests;
    }

    public void discardInboundPackets() {
        setPacketHandler(new BedrockPacketHandler() {
        });
    }

    public BedrockPacketWrapper currentInboundPacket() {
        return currentInboundPacket;
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
        super.onClose();
        if (disconnectClientOnClose && connection != null && connection.client().isConnected()) {
            // During a join sequence the next candidate is already being tried, and kicking here
            // would end the session that sequence exists to save. JoinFailover disconnects instead,
            // once the list runs out.
            if (connection.isJoinSequenceActive() && !connection.hasClientJoinedWorld()) {
                return;
            }
            connection.client().disconnect("Backend disconnected");
        }
    }
}
