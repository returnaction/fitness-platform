package io.github.returnaction.fitness.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.apache.hc.core5.util.Timeout.ofSeconds;

import com.sun.net.httpserver.HttpServer;
import io.github.returnaction.fitness.gateway.security.jwks.JwksInitializer;
import io.github.returnaction.fitness.gateway.security.session.SessionVerificationClient;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.SocketTimeoutException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import java.util.UUID;
import org.mockito.ArgumentMatchers;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.Timeout;
import org.apache.hc.core5.util.TimeValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(15)
class HttpDeadlineTest {

    private static final AtomicInteger MODE = new AtomicInteger();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static volatile CountDownLatch streamClosed = new CountDownLatch(1);
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer SERVER = server();

    @MockitoBean
    private JwksInitializer keys;
    @MockitoBean
    private SessionVerificationClient sessions;
    @MockitoBean
    private JwtDecoder decoder;
    @LocalServerPort
    private int port;
    @Autowired
    private ScheduledThreadPoolExecutor scheduler;
    @Autowired
    private PoolingHttpClientConnectionManager connections;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("gateway.request.timeout", () -> "500ms");
        properties.add("gateway.upstreams.services.IDENTITY.base-url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(WORKERS);
            server.createContext("/", exchange -> {
                CALLS.incrementAndGet();
                int mode = MODE.get();
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    if (mode == 1) {
                        Thread.sleep(1800);
                    }
                    if (mode == 4) {
                        return;
                    }
                    if (mode == 5) {
                        exchange.sendResponseHeaders(200, 64L * 1024 * 1024);
                        byte[] chunk = new byte[65536];
                        try {
                            for (int i = 0; i < 1024; i++) {
                                exchange.getResponseBody().write(chunk);
                            }
                        } finally {
                            streamClosed.countDown();
                        }
                        return;
                    }
                    if (mode == 6) {
                        Thread.sleep(450);
                    }
                    exchange.sendResponseHeaders(200, mode == 2 || mode == 3 ? 20000 : 2);
                    if (mode == 2 || mode == 3) {
                        exchange.getResponseBody().write(new byte[mode == 2 ? 4 : 10000]);
                        exchange.getResponseBody().flush();
                        Thread.sleep(1800);
                    } else {
                        exchange.getResponseBody().write("ok".getBytes(StandardCharsets.UTF_8));
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // Expected when Gateway cancels a test exchange.
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @BeforeEach
    void ready() {
        when(keys.ready()).thenReturn(true);
        MODE.set(0);
        when(decoder.decode("valid")).thenReturn(Jwt.withTokenValue("valid")
                .header("alg", "ES256")
                .subject(UUID.randomUUID().toString())
                .claim("sid", UUID.randomUUID().toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build());
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
        WORKERS.shutdownNow();
    }

    @Test
    void timesOutHeadersAndBufferedBodyThenServesNextRequest() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (int mode : new int[]{1, 2}) {
                MODE.set(mode);
                long start = System.nanoTime();
                var response = send(client);
                assertThat(response.statusCode()).isEqualTo(504);
                assertThat(response.body()).contains("upstream_timeout", "requestId");
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1400));
                assertThat(scheduler.getQueue()).isEmpty();
                MODE.set(0);
                assertThat(send(client).body()).isEqualTo("ok");
            }
        }
    }

    @Test
    void cancelsSlowIncomingBody() throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2500);
            socket.getOutputStream()
                    .write(("POST /api/v1/auth/login HTTP/1.1\r\nHost: localhost\r\n"
                            + "Content-Type: application/json\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{")
                            .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var bytes = new ByteArrayOutputStream();
            try {
                int value;
                while ((value = socket.getInputStream().read()) != -1) {
                    bytes.write(value);
                }
            } catch (SocketTimeoutException failure) {
                throw new AssertionError("Incomplete response: " + bytes.toString(StandardCharsets.UTF_8)
                        + "; scheduled cancellations=" + scheduler.getQueue().size(), failure);
            }
            String response = bytes.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("504", "upstream_timeout");
            assertThat(scheduler.getQueue()).isEmpty();
        }
    }

    @Test
    void abortsCommittedBodyWithoutAppendingJson() throws Exception {
        MODE.set(3);
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2500);
            socket.getOutputStream()
                    .write(("POST /api/v1/auth/login HTTP/1.1\r\nHost: localhost\r\n"
                            + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertThat(response).contains("200").doesNotContain("upstream_timeout", "<html", "requestId");
            assertThat(response.length()).isLessThan(15000);
            assertThat(scheduler.getQueue()).isEmpty();
        }
    }

    @Test
    void mapsUpstreamDisconnectTo502WithoutRetry() throws Exception {
        MODE.set(4);
        int before = CALLS.get();
        try (var client = HttpClient.newHttpClient()) {
            var response = send(client);
            assertThat(response.statusCode()).isEqualTo(502);
            assertThat(response.body()).contains("upstream_unavailable");
            assertThat(CALLS.get()).isEqualTo(before + 1);
            assertThat(scheduler.getQueue()).isEmpty();
        }
    }

    private HttpResponse<String> send(HttpClient client) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void expiredBudgetAfterSessionNeverStartsHttp() throws Exception {
        when(sessions.isActive(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenAnswer(invocation -> {
                    Thread.sleep(600);
                    return true;
                });
        int before = CALLS.get();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/logout-all"))
                            .header("Authorization", "Bearer valid")
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(504);
            assertThat(CALLS.get()).isEqualTo(before);
            assertThat(scheduler.getQueue()).isEmpty();
        }
    }

    @Test
    void cancelsWhenClientStopsReadingResponse() throws Exception {
        MODE.set(5);
        streamClosed = new CountDownLatch(1);
        try (var socket = new Socket()) {
            socket.setReceiveBufferSize(1024);
            socket.connect(new InetSocketAddress("127.0.0.1", port));
            socket.getOutputStream()
                    .write(("POST /api/v1/auth/login HTTP/1.1\r\nHost: localhost\r\n"
                            + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            assertThat(streamClosed.await(2, TimeUnit.SECONDS)).isTrue();
        }
        MODE.set(0);
        try (var client = HttpClient.newHttpClient()) {
            assertThat(send(client).body()).isEqualTo("ok");
            assertThat(scheduler.getQueue()).isEmpty();
        }
    }

    @Test
    void completionNearDeadlineDoesNotCancelNextRequests() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (int i = 0; i < 6; i++) {
                MODE.set(6);
                assertThat(send(client).statusCode()).isIn(200, 504);
                MODE.set(0);
                assertThat(send(client).body()).isEqualTo("ok");
            }
            Thread.sleep(600);
            assertThat(send(client).body()).isEqualTo("ok");
            assertThat(scheduler.getQueue()).isEmpty();
        }
    }

    @Test
    void cancelsWaitingForPoolLeaseWithoutStartingUpstream() throws Exception {
        int maximum = connections.getMaxTotal();
        connections.setMaxTotal(1);
        var route = new HttpRoute(new HttpHost("http", "127.0.0.1", SERVER.getAddress().getPort()));
        var held = connections.lease("test-held", route, null).get(ofSeconds(1));
        int before = CALLS.get();
        try (var client = HttpClient.newHttpClient()) {
            long start = System.nanoTime();
            assertThat(send(client).statusCode()).isEqualTo(504);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1400));
            assertThat(CALLS.get()).isEqualTo(before);
        } finally {
            connections.release(held, null, TimeValue.ZERO_MILLISECONDS);
            connections.setMaxTotal(maximum);
        }
        assertThat(connections.getTotalStats().getPending()).isZero();
        assertThat(CALLS.get()).isEqualTo(before);
        try (var client = HttpClient.newHttpClient()) {
            assertThat(send(client).body()).isEqualTo("ok");
        }
    }

    @Test
    void firewallUsesCommonErrorBeforeForwarding() throws Exception {
        int before = CALLS.get();
        try (var client = HttpClient.newHttpClient()) {
            var response = client
                    .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login;bad"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("invalid_request", "requestId");
            assertThat(CALLS.get()).isEqualTo(before);
        }
    }

    @Test
    void tricklingUploadDoesNotRestartBudgetOnEachRead() throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            socket.getOutputStream()
                    .write(("POST /api/v1/auth/login HTTP/1.1\r\nHost: localhost\r\n"
                            + "Content-Type: application/json\r\nContent-Length: 100\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            var writer = Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 30; i++) {
                        socket.getOutputStream().write('x');
                        socket.getOutputStream().flush();
                        Thread.sleep(100);
                    }
                } catch (IOException ignored) {
                    // Gateway closes the upload at its absolute deadline.
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            long started = System.nanoTime();
            try {
                var response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(response).contains("504", "upstream_timeout");
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1400));
            } finally {
                writer.interrupt();
                writer.join(1000);
            }
        }
    }
}
