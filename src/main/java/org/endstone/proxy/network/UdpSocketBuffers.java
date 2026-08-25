package org.endstone.proxy.network;

import io.netty.channel.socket.DatagramChannel;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Applies and reports the real kernel buffer sizes behind a RakNet UDP channel. */
public final class UdpSocketBuffers {
    private static final Set<String> REPORTED_ROLES = ConcurrentHashMap.newKeySet();

    private UdpSocketBuffers() {
    }

    public static void configure(DatagramChannel channel, int receiveBytes, int sendBytes, String role) {
        try {
            channel.config().setReceiveBufferSize(receiveBytes);
            channel.config().setSendBufferSize(sendBytes);
            int actualReceive = channel.config().getReceiveBufferSize();
            int actualSend = channel.config().getSendBufferSize();
            if (!REPORTED_ROLES.add(role)) {
                return;
            }
            if (actualReceive < receiveBytes || actualSend < sendBytes) {
                System.out.printf(
                        "WARNING: UDP buffers for %s were capped by the OS: receive=%d/%d requested, "
                                + "send=%d/%d requested. On Linux raise net.core.rmem_max and "
                                + "net.core.wmem_max or packet loss can make long-lived RakNet sessions lag.%n",
                        role, actualReceive, receiveBytes, actualSend, sendBytes
                );
            } else {
                System.out.printf(
                        "UDP buffers for %s: receive=%d bytes, send=%d bytes.%n",
                        role, actualReceive, actualSend
                );
            }
        } catch (RuntimeException exception) {
            if (REPORTED_ROLES.add(role)) {
                System.out.printf(
                        "WARNING: could not configure UDP buffers for %s (%s); packet bursts may be dropped.%n",
                        role, exception.getMessage()
                );
            }
        }
    }
}
