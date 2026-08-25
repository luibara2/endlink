package org.endstone.proxy.config;

/** Kernel UDP socket buffers used by the shared listener and each backend connection. */
public record UdpBufferConfig(
        int listenerReceiveBytes,
        int backendReceiveBytes,
        int sendBytes
) {
    public static final int DEFAULT_LISTENER_RECEIVE_BYTES = 8 * 1024 * 1024;
    public static final int DEFAULT_BACKEND_RECEIVE_BYTES = 4 * 1024 * 1024;
    public static final int DEFAULT_SEND_BYTES = 1024 * 1024;

    public UdpBufferConfig {
        if (listenerReceiveBytes <= 0 || backendReceiveBytes <= 0 || sendBytes <= 0) {
            throw new IllegalArgumentException("UDP socket buffer sizes must be positive");
        }
    }

    public static UdpBufferConfig defaults() {
        return new UdpBufferConfig(
                DEFAULT_LISTENER_RECEIVE_BYTES,
                DEFAULT_BACKEND_RECEIVE_BYTES,
                DEFAULT_SEND_BYTES
        );
    }
}
