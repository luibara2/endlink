package org.endstone.proxy.backend;

/**
 * A host and port to send a reconnecting client to.
 *
 * <p>Its own type because the strings this is parsed from are not as simple as they look. A Bedrock
 * client's {@code ServerAddress} claim carries the port — {@code play.example.com:19132} — while
 * {@code TransferPacket} wants the host on its own and the port as a number. Handing the client the
 * joined form back produces "invalid IP address" and drops it off the proxy entirely, which is worse
 * than not offering the move at all.</p>
 *
 * <p>IPv6 is the reason this cannot just split on the last colon: a bare {@code ::1} is all colons
 * and no port, and {@code [::1]:19132} puts the host in brackets. Both reach a proxy on the same
 * machine, which is exactly where this gets exercised first.</p>
 */
record ReconnectAddress(String host, int port) {

    /**
     * @param raw           {@code host}, {@code host:port}, {@code [v6]} or {@code [v6]:port}
     * @param fallbackPort  used when the string carries no usable port
     * @return the parsed address, or null when there is no host to speak of
     */
    static ReconnectAddress parse(String raw, int fallbackPort) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }

        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) {
                return null;
            }
            String host = value.substring(1, close);
            int port = value.length() > close + 1 && value.charAt(close + 1) == ':'
                    ? parsePort(value.substring(close + 2), fallbackPort)
                    : fallbackPort;
            return host.isBlank() ? null : new ReconnectAddress(host, port);
        }

        int separator = value.indexOf(':');
        // More than one colon and no brackets is a bare IPv6 literal, not a host and port.
        if (separator < 0 || value.indexOf(':', separator + 1) >= 0) {
            return new ReconnectAddress(value, fallbackPort);
        }

        String host = value.substring(0, separator);
        if (host.isBlank()) {
            return null;
        }
        return new ReconnectAddress(host, parsePort(value.substring(separator + 1), fallbackPort));
    }

    private static int parsePort(String value, int fallbackPort) {
        try {
            int port = Integer.parseInt(value.trim());
            // A nonsense port is not worth refusing the move over; the listener's own is right in
            // every ordinary install.
            return port > 0 && port <= 65_535 ? port : fallbackPort;
        } catch (NumberFormatException exception) {
            return fallbackPort;
        }
    }
}
