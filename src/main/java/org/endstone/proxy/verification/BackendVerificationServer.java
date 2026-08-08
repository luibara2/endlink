package org.endstone.proxy.verification;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.endstone.proxy.config.BackendVerificationConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BackendVerificationServer implements AutoCloseable {
    private final BackendVerificationConfig config;
    private final PendingJoinRegistry pendingJoins;
    private final VerificationSigner signer;
    private HttpServer server;
    private ExecutorService executor;

    public BackendVerificationServer(BackendVerificationConfig config, PendingJoinRegistry pendingJoins, Clock clock) {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        if (pendingJoins == null) {
            throw new IllegalArgumentException("pendingJoins cannot be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock cannot be null");
        }
        this.config = config;
        this.pendingJoins = pendingJoins;
        this.signer = new VerificationSigner(config.sharedSecret(), clock, config.requestSkewMillis());
    }

    public void start() throws IOException {
        if (!config.enabled()) {
            return;
        }
        server = HttpServer.create(config.listenAddress(), 0);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "endstone-backend-verification");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/verify", this::handleVerify);
        server.start();
        InetSocketAddress address = server.getAddress();
        System.out.printf("Backend verification endpoint listening on %s:%d.%n",
                address.getHostString(),
                address.getPort()
        );
        if (BackendVerificationConfig.DEFAULT_SHARED_SECRET.equals(config.sharedSecret())) {
            System.out.println("WARNING: backendVerification.sharedSecret is still the default development value.");
        }
    }

    public PendingJoinRegistry pendingJoins() {
        return pendingJoins;
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(1);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void handleVerify(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "method not allowed");
            return;
        }

        try {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            // XUID/UUID may be empty: BDS 1.26.10+ in offline mode does not trust self-signed
            // OIDC `xid`/`identity` claims. Lookup matches on display name. We still accept
            // whatever values the plugin sent (incl. blank) so the HMAC signature math stays
            // consistent on both sides.
            VerificationRequest request = new VerificationRequest(
                    query.getOrDefault("xuid", ""),
                    query.getOrDefault("uuid", ""),
                    require(query, "name"),
                    require(query, "address"),
                    Long.parseLong(require(query, "timestamp")),
                    require(query, "nonce")
            );

            if (!signer.isFresh(request.timestampMillis())) {
                System.out.printf(
                        "WARNING: Backend verifier request for %s from %s was stale. Check backend/proxy clocks if proxied joins are rejected.%n",
                        request.name(),
                        exchange.getRemoteAddress()
                );
                send(exchange, 401, "stale verification request");
                return;
            }
            if (!signer.verify(request, require(query, "signature"))) {
                System.out.printf(
                        "WARNING: Backend verifier request for %s from %s had a bad signature. The EndlinkGuard plugin is installed, but its shared_secret does not match backendVerification.sharedSecret; proxied joins will be rejected until both match.%n",
                        request.name(),
                        exchange.getRemoteAddress()
                );
                send(exchange, 401, "bad signature");
                return;
            }

            PendingJoinRegistry.ConsumedJoin consumed = pendingJoins.consumeJoin(request);
            if (consumed.result() == PendingJoinRegistry.ConsumeResult.ACCEPTED) {
                send(exchange, 200, verificationResponse(consumed.pendingJoin()));
                return;
            }
            send(exchange, 403, consumed.result().name().toLowerCase());
        } catch (Exception exception) {
            send(exchange, 400, "bad request");
        }
    }

    static String verificationResponse(PendingJoin pendingJoin) {
        return "{"
                + "\"status\":\"ok\","
                + "\"xuid\":\"" + jsonEscape(pendingJoin.xuid()) + "\","
                + "\"uuid\":\"" + jsonEscape(pendingJoin.uuid()) + "\","
                + "\"name\":\"" + jsonEscape(pendingJoin.name()) + "\","
                + "\"backend\":\"" + jsonEscape(pendingJoin.backendName()) + "\","
                + "\"real_ip\":\"" + jsonEscape(pendingJoin.clientIp()) + "\","
                + "\"real_port\":" + pendingJoin.clientPort()
                + "}";
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.toString();
    }

    private static String require(Map<String, String> query, String key) {
        String value = query.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing query parameter: " + key);
        }
        return value;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            if (separator < 0) {
                continue;
            }
            query.put(decode(pair.substring(0, separator)), decode(pair.substring(separator + 1)));
        }
        return query;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (body.startsWith("{")) {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
