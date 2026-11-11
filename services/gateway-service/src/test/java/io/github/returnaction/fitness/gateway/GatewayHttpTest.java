package io.github.returnaction.fitness.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.github.returnaction.fitness.gateway.exception.GatewayException;
import io.github.returnaction.fitness.gateway.security.SessionAccess;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayHttpTest {
    private static final ECKey KEY = key();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final Map<String, List<String>> HEADERS = new ConcurrentHashMap<>();
    private static volatile String body;
    private static volatile int upstreamStatus = 200;
    private static volatile boolean slow;
    private static volatile boolean disconnect;
    private static final HttpServer UPSTREAM = upstream();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    @LocalServerPort
    private int port;
    @MockitoBean
    private SessionAccess sessions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private io.github.returnaction.fitness.gateway.security.RequestLimits limits;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.identity-url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
        registry.add("gateway.jwks-url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort() + "/keys");
    }

    @BeforeEach
    void reset() {
        CALLS.set(0);
        HEADERS.clear();
        slow = false;
        disconnect = false;
        upstreamStatus = 200;
    }
    @AfterAll
    static void close() {
        CLIENT.close();
        UPSTREAM.stop(0);
    }

    @Test
    void publicRoutesPreserveBodiesStatusesAndHeaders() throws Exception {
        for (String path : List.of("register", "login", "refresh", "logout")) {
            upstreamStatus = path.equals("logout") ? 204 : 201;
            var result = send(path, "POST", "{\"secret\":\"synthetic-only\"}",
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertThat(result.statusCode()).isEqualTo(upstreamStatus);
            assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(body).contains("synthetic-only");
            assertThat(HEADERS).containsKey("Idempotency-key");
        }
        verifyNoInteractions(sessions);
    }

    @Test
    void rejectsUnknownPathsMethodsAndOversizedChunkedBody() throws Exception {
        assertThat(send("../admin", "POST", "", Map.of()).statusCode()).isIn(400, 404);
        assertThat(send("login", "GET", "", Map.of()).statusCode()).isEqualTo(405);
        assertThat(send("login", "POST", "x".repeat(16385), Map.of()).statusCode()).isEqualTo(413);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(new byte[16385])))
                .build();
        assertThat(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
        assertThat(CALLS).hasValue(0);
    }

    @Test
    void stripsForgedIdentityAndForwardedHeaders() throws Exception {
        var result = send("login", "POST", "{}", Map.of("X-User-Id", "administrator", "X-Roles", "SUPER_ADMIN",
                "Forwarded", "for=evil", "X-Forwarded-For", "1.2.3.4", "Cookie", "secret=value"));
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(HEADERS).doesNotContainKeys("X-user-id", "X-roles", "Forwarded", "Cookie");
        assertThat(HEADERS.get("X-forwarded-for")).containsExactly("127.0.0.1");
        assertThat(result.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void checksJwtAndSessionBeforeProxying() throws Exception {
        String token = token("test-identity", Instant.now().plusSeconds(60));
        assertThat(send("logout-all", "POST", "", Map.of("Authorization", "Bearer " + token)).statusCode())
                .isEqualTo(200);
        verify(sessions).check(any(), any());
        assertThat(CALLS).hasValue(1);
    }

    @Test
    void rejectsMissingExpiredWrongIssuerAndForgedTokens() throws Exception {
        assertThat(send("logout-all", "POST", "", Map.of()).statusCode()).isEqualTo(401);
        for (String token : List.of("synthetic-invalid-token", token("wrong", Instant.now().plusSeconds(60)),
                token("test-identity", Instant.now().minusSeconds(1)))) {
            var result = send("logout-all", "POST", "", Map.of("Authorization", "Bearer " + token));
            assertThat(result.statusCode()).isEqualTo(401);
            assertThat(result.body()).contains("invalid_access_token").doesNotContain(token);
        }
        verifyNoInteractions(sessions);
        assertThat(CALLS).hasValue(0);
    }

    @Test
    void sessionRevocationAndUnavailableFailClosed() throws Exception {
        String token = token("test-identity", Instant.now().plusSeconds(60));
        doThrow(GatewayException.invalid()).when(sessions).check(any(), any());
        assertThat(send("logout-all", "POST", "", Map.of("Authorization", "Bearer " + token)).statusCode())
                .isEqualTo(401);
        doThrow(GatewayException.unavailable()).when(sessions).check(any(), any());
        assertThat(send("logout-all", "POST", "", Map.of("Authorization", "Bearer " + token)).statusCode())
                .isEqualTo(503);
        assertThat(CALLS).hasValue(0);
    }

    @Test
    void preservesUpstreamErrorsAndDoesNotRetryPostOnTimeout() throws Exception {
        upstreamStatus = 403;
        assertThat(send("login", "POST", "{}", Map.of()).statusCode()).isEqualTo(403);
        CALLS.set(0);
        slow = true;
        assertThat(send("refresh", "POST", "{}", Map.of()).statusCode()).isEqualTo(504);
        assertThat(CALLS).hasValue(1);
    }

    @Test
    void managementIsNotExposedOnApiPortAndRequestIdIsPreserved() throws Exception {
        var url = "http://127.0.0.1:" + port + "/actuator/health";
        assertThat(
                CLIENT.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(404);
        int managementPort = environment.getRequiredProperty("local.management.port", Integer.class);
        var health = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/health"))
                .GET()
                .build();
        assertThat(CLIENT.send(health, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        String id = UUID.randomUUID().toString();
        assertThat(send("login", "POST", "{}", Map.of("X-Request-Id", id)).headers().firstValue("X-Request-Id"))
                .contains(id);
        assertThat(HEADERS.get("X-request-id")).containsExactly(id);
    }

    @Test
    void rateLimitReturnsRetryAfterAndDoesNotReachUpstream() throws Exception {
        doThrow(new GatewayException(429, "rate_limited")).when(limits).check(any());
        var response = send("login", "POST", "{}", Map.of());
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).contains("60");
        assertThat(CALLS).hasValue(0);
    }

    @Test
    void lostConnectionDoesNotReplayPost() throws Exception {
        disconnect = true;
        assertThat(send("refresh", "POST", "{}", Map.of()).statusCode()).isEqualTo(502);
        assertThat(CALLS).hasValue(1);
    }

    private HttpResponse<String> send(String path, String method, String payload, Map<String, String> headers)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/" + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(payload));
        headers.forEach(request::header);
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(String issuer, Instant expiry) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer)
                .audience("fitness-gateway")
                .subject(UUID.randomUUID().toString())
                .claim("sid", UUID.randomUUID().toString())
                .jwtID(UUID.randomUUID().toString())
                .claim("roles", List.of("USER"))
                .issueTime(Date.from(Instant.now().minusSeconds(10)))
                .expirationTime(Date.from(expiry))
                .build();
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("test").build(), claims);
        token.sign(new ECDSASigner(KEY));
        return token.serialize();
    }

    private static ECKey key() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID("test").generate();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static HttpServer upstream() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().equals("/keys")) {
                    byte[] bytes = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                    return;
                }
                CALLS.incrementAndGet();
                HEADERS.clear();
                HEADERS.putAll(exchange.getRequestHeaders());
                body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (disconnect) {
                    exchange.close();
                    return;
                }
                if (slow) {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] bytes = "{\"result\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(upstreamStatus, upstreamStatus == 204 ? -1 : bytes.length);
                if (upstreamStatus != 204)
                    exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
