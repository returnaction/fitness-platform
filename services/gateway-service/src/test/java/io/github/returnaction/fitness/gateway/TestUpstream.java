package io.github.returnaction.fitness.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class TestUpstream implements AutoCloseable {

    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicReference<Received> received = new AtomicReference<>();
    private final HttpServer server;

    TestUpstream() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                var headers = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
                exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
                byte[] body = exchange.getRequestBody().readAllBytes();
                received.set(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(), headers,
                        new String(body, StandardCharsets.UTF_8)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("X-Request-Id", "upstream-id");
                exchange.getResponseHeaders().set("Set-Cookie", "upstream=private");
                exchange.getResponseHeaders().set("Location", baseUrl() + "/redirect-target");
                byte[] response = "{\"result\":\"unchanged\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(), response.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(response);
                }
            });
            server.start();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    record Received(String method, String path, Map<String, List<String>> headers, String body) {

        String header(String name) {
            var values = headers.get(name);
            return values == null ? null : values.getFirst();
        }
    }
}
