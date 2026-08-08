package org.endstone.proxy.backend;

import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.BedrockPong;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public final class BackendProtocolDetector {
    private static final byte UNCONNECTED_PING = 0x01;
    private static final byte UNCONNECTED_PONG = 0x1c;
    private static final byte[] RAKNET_MAGIC = new byte[]{
            0x00, (byte) 0xff, (byte) 0xff, 0x00,
            (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd,
            0x12, 0x34, 0x56, 0x78
    };

    private final int timeoutMillis;
    private final int attempts;

    public BackendProtocolDetector() {
        this(1_500, 2);
    }

    BackendProtocolDetector(int timeoutMillis, int attempts) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        if (attempts <= 0) {
            throw new IllegalArgumentException("attempts must be positive");
        }
        this.timeoutMillis = timeoutMillis;
        this.attempts = attempts;
    }

    public BedrockPong detect(InetSocketAddress address) throws IOException {
        IOException lastException = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                Optional<BedrockPong> pong = ping(address);
                if (pong.isPresent()) {
                    return pong.get();
                }
            } catch (IOException exception) {
                lastException = exception;
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new IOException("Backend did not return a Bedrock pong: " + address);
    }

    private Optional<BedrockPong> ping(InetSocketAddress address) throws IOException {
        byte[] request = request();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMillis);
            socket.send(new DatagramPacket(request, request.length, address));

            byte[] response = new byte[4096];
            DatagramPacket packet = new DatagramPacket(response, response.length);
            socket.receive(packet);
            return parse(response, packet.getLength());
        }
    }

    private static byte[] request() {
        ByteBuffer buffer = ByteBuffer.allocate(1 + Long.BYTES + RAKNET_MAGIC.length + Long.BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.put(UNCONNECTED_PING);
        buffer.putLong(System.currentTimeMillis());
        buffer.put(RAKNET_MAGIC);
        buffer.putLong(ThreadLocalRandom.current().nextLong());
        return buffer.array();
    }

    private static Optional<BedrockPong> parse(byte[] response, int length) {
        if (length < 1 + Long.BYTES + Long.BYTES + RAKNET_MAGIC.length + Short.BYTES
                || response[0] != UNCONNECTED_PONG) {
            return Optional.empty();
        }

        int magicOffset = 1 + Long.BYTES + Long.BYTES;
        for (int i = 0; i < RAKNET_MAGIC.length; i++) {
            if (response[magicOffset + i] != RAKNET_MAGIC[i]) {
                return Optional.empty();
            }
        }

        int lengthOffset = magicOffset + RAKNET_MAGIC.length;
        int pongLength = Short.toUnsignedInt(ByteBuffer.wrap(response, lengthOffset, Short.BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .getShort());
        int pongOffset = lengthOffset + Short.BYTES;
        if (pongLength <= 0 || pongOffset + pongLength > length) {
            return Optional.empty();
        }

        return Optional.of(BedrockPong.fromRakNet(Unpooled.wrappedBuffer(response, pongOffset, pongLength)));
    }
}
