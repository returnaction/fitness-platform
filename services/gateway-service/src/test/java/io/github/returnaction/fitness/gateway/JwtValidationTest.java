package io.github.returnaction.fitness.gateway;

import io.github.returnaction.fitness.gateway.security.session.SessionVerificationClient;
import io.github.returnaction.fitness.gateway.security.session.SessionVerificationUnavailableException;
import io.github.returnaction.fitness.gateway.timeout.RequestDeadline;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import io.github.returnaction.fitness.gateway.routing.GatewayRouteCatalog;
import io.github.returnaction.fitness.gateway.routing.RouteAccess;
import io.github.returnaction.fitness.gateway.security.jwks.JwksInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class JwtValidationTest {

    private static final TestUpstream UPSTREAM = new TestUpstream();

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("gateway.upstreams.services.IDENTITY.base-url", UPSTREAM::baseUrl);
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.close();
    }

    @MockitoBean
    private SessionVerificationClient sessions;

    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
    private static final ECKey KEY = key();
    @MockitoBean
    private JwksInitializer keys;
    @MockitoBean
    private Clock clock;
    @MockitoSpyBean
    private JwtDecoder decoder;
    @LocalServerPort
    private int port;

    private static ECKey key() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID("trusted").generate();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @BeforeEach
    void configureTrustAndTime() {
        UPSTREAM.calls.set(0);
        UPSTREAM.status.set(200);
        when(sessions.isActive(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(true);
        when(keys.ready()).thenReturn(true);
        when(keys.keys()).thenReturn(List.of(KEY.toPublicJWK()));
        when(clock.instant()).thenReturn(NOW);
        clearInvocations(decoder);
        clearInvocations(sessions);
    }

    private Map<String, Object> claims() {
        var claims = new HashMap<String, Object>();
        claims.put("sub", UUID.randomUUID().toString());
        claims.put("sid", UUID.randomUUID().toString());
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iss", "test-identity");
        claims.put("aud", List.of("test-gateway"));
        claims.put("iat", NOW.getEpochSecond());
        claims.put("exp", NOW.plusSeconds(900).getEpochSecond());
        return claims;
    }

    private String sign(Map<String, Object> claims, ECKey signingKey, String kid) throws Exception {
        var token = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(kid).build(), new Payload(claims));
        token.sign(new ECDSASigner(signingKey));
        return token.serialize();
    }

    private String sign(Map<String, Object> claims) throws Exception {
        return sign(claims, KEY, "trusted");
    }

    @Test
    void acceptsValidClaimsAndInclusiveIatNbfLifetimeBoundaries() throws Exception {
        var claims = claims();
        claims.put("nbf", NOW.getEpochSecond());
        claims.put("roles", List.of("UNKNOWN", "USER"));
        var jwt = decoder.decode(sign(claims));
        assertThat(jwt.getSubject()).isEqualTo(claims.get("sub"));
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
        claims.remove("roles");
        assertThat(decoder.decode(sign(claims))).isNotNull();
        claims.put("roles", null);
        assertThat(decoder.decode(sign(claims))).isNotNull();
        claims.put("roles", List.of());
        assertThat(decoder.decode(sign(claims))).isNotNull();
    }

    @Test
    void rejectsMissingAndWrongTypesWithoutConverterDefaults() throws Exception {
        for (String name : List.of("sub", "sid", "jti", "iat", "exp", "iss", "aud")) {
            var absent = claims();
            absent.remove(name);
            assertThatThrownBy(() -> decoder.decode(sign(absent))).as("missing " + name)
                    .isInstanceOf(JwtException.class);
            var invalid = claims();
            invalid.put(name, Map.of("invalid", true));
            assertThatThrownBy(() -> decoder.decode(sign(invalid))).as("type " + name).isInstanceOf(JwtException.class);
        }
        for (Object roles : List.of("USER", List.of(1), Arrays.asList("USER", null))) {
            var invalid = claims();
            invalid.put("roles", roles);
            assertThatThrownBy(() -> decoder.decode(sign(invalid))).isInstanceOf(JwtException.class);
        }
        for (String field : List.of("sub", "sid", "jti")) {
            for (String value : List.of("1-1-1-1-1", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA", "bad")) {
                var invalid = claims();
                invalid.put(field, value);
                assertThatThrownBy(() -> decoder.decode(sign(invalid))).isInstanceOf(JwtException.class);
            }
        }
    }

    @Test
    void rejectsInvalidTimeIssuerAudienceAndSignature() throws Exception {
        for (var entry : Map.<String, Object>of(
                "iat", NOW.plusSeconds(1).getEpochSecond(),
                "exp", NOW.plusSeconds(901).getEpochSecond(),
                "nbf", NOW.plusSeconds(1).getEpochSecond(),
                "iss", "wrong",
                "aud", List.of("wrong")).entrySet()) {
            var invalid = claims();
            invalid.put(entry.getKey(), entry.getValue());
            assertThatThrownBy(() -> decoder.decode(sign(invalid))).isInstanceOf(JwtException.class);
        }
        for (long expiry : List.of(NOW.getEpochSecond(), NOW.minusSeconds(1).getEpochSecond())) {
            var invalid = claims();
            invalid.put("iat", NOW.minusSeconds(10).getEpochSecond());
            invalid.put("exp", expiry);
            assertThatThrownBy(() -> decoder.decode(sign(invalid))).isInstanceOf(JwtException.class);
        }
        assertThatThrownBy(() -> decoder.decode(sign(claims(), key(), "trusted"))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign(claims(), KEY, "unknown"))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign(claims(), KEY, null))).isInstanceOf(JwtException.class);
        var hs = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("trusted").build(),
                new Payload(claims()));
        hs.sign(new MACSigner(new byte[32]));
        assertThatThrownBy(() -> decoder.decode(hs.serialize())).isInstanceOf(JwtException.class);
    }

    @Test
    void publicOperationsAndEarlyErrorsNeverInvokeDecoder() throws Exception {
        var expired = claims();
        expired.put("exp", NOW.minusSeconds(1).getEpochSecond());
        try (var client = HttpClient.newHttpClient()) {
            for (var route : GatewayRouteCatalog.routes()) {
                if (route.access() != RouteAccess.PUBLIC) {
                    continue;
                }
                for (String token : List.of("", "malformed", sign(claims()), sign(expired))) {
                    assertThat(request(client, route.path(), token).statusCode()).isEqualTo(200);
                    assertThat(UPSTREAM.received.get().header("Authorization")).isNull();
                }
            }
            assertThat(request(client, "/unknown", "bad").statusCode()).isEqualTo(404);
            assertThat(request(client, "/api/v1/auth/logout-all?x=1", "bad").statusCode()).isEqualTo(400);
            assertThat(UPSTREAM.calls.get()).isEqualTo(16);
        }
        verify(decoder, never()).decode(ArgumentMatchers.anyString());
        verify(sessions, never()).isActive(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void protectedRouteForwardsOnlyAfterValidJwtAndActiveSession() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var invalid = request(client, "/api/v1/auth/logout-all", sign(claims(), KEY, "unknown"));
            assertThat(invalid.statusCode()).isEqualTo(401);
            assertThat(invalid.body()).contains("invalid_access_token", "request_id");
            assertThat(invalid.headers().firstValue("WWW-Authenticate")).contains("Bearer");
            Mockito.verifyNoInteractions(sessions);
            assertThat(UPSTREAM.calls.get()).isZero();
            var valid = request(client, "/api/v1/auth/logout-all", sign(claims()));
            assertThat(valid.statusCode()).isEqualTo(200);
            assertThat(UPSTREAM.calls.get()).isEqualTo(1);
            assertThat(request(client, "/api/v1/auth/logout-all", "").statusCode()).isEqualTo(401);
            assertThat(UPSTREAM.calls.get()).isEqualTo(1);
        }
    }

    @Test
    void sessionInvalidOrUnavailableIsRejectedWithSameRequestId() throws Exception {
        var claims = claims();
        var user = UUID.fromString((String) claims.get("sub"));
        var session = UUID.fromString((String) claims.get("sid"));
        var requestId = new AtomicReference<UUID>();
        when(sessions.isActive(ArgumentMatchers.eq(user), ArgumentMatchers.eq(session),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenAnswer(invocation -> {
                    requestId.set(invocation.getArgument(2));
                    RequestDeadline deadline = invocation.getArgument(3);
                    assertThat(deadline.remainingNanos()).isPositive().isLessThanOrEqualTo(3_000_000_000L);
                    return false;
                });
        try (var client = HttpClient.newHttpClient()) {
            var invalid = request(client, "/api/v1/auth/logout-all", sign(claims));
            assertThat(invalid.statusCode()).isEqualTo(401);
            assertThat(invalid.headers().firstValue("X-Request-Id")).contains(requestId.get().toString());
            assertThat(invalid.body()).contains(requestId.get().toString(), "invalid_access_token");
            when(sessions.isActive(ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenThrow(
                            new SessionVerificationUnavailableException(
                                    "private transport diagnostics"));
            var failed = request(client, "/api/v1/auth/logout-all", sign(claims));
            assertThat(failed.statusCode()).isEqualTo(503);
            assertThat(failed.body()).contains("identity_unavailable").doesNotContain("private transport diagnostics");
            assertThat(UPSTREAM.calls.get()).isZero();
        }
    }

    @Test
    void forwardsFiveOperationsWithOnlyAllowedHeadersAndOriginalBody() throws Exception {
        String token = sign(claims());
        String body = "{ \"value\" : \"исходное тело\" }";
        try (var client = HttpClient.newHttpClient()) {
            for (var route : GatewayRouteCatalog.routes()) {
                var outgoing = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route.path()))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("Idempotency-Key", "operation-key")
                        .header("X-Request-Id", "forged-id")
                        .header("X-User-Id", "forged-user")
                        .header("X-Roles", "ADMIN")
                        .header("Cookie", "client=private")
                        .header("Forwarded", "for=203.0.113.7;proto=https")
                        .header("X-Forwarded-For", "203.0.113.7")
                        .header("X-Forwarded-Host", "evil.example")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Extra", "remove")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                var response = client.send(outgoing, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).isEqualTo("{\"result\":\"unchanged\"}");
                var received = UPSTREAM.received.get();
                assertThat(received.method()).isEqualTo("POST");
                assertThat(received.path()).isEqualTo(route.path());
                assertThat(received.body()).isEqualTo(body);
                assertThat(received.header("Content-Type")).isEqualTo("application/json");
                assertThat(received.header("Accept")).isEqualTo("application/json");
                assertThat(received.header("Idempotency-Key")).isEqualTo("operation-key");
                assertThat(received.header("Authorization")).isEqualTo(
                        route.access() == RouteAccess.PUBLIC ? null : "Bearer " + token);
                assertThat(received.header("X-Forwarded-For")).isEqualTo("127.0.0.1");
                assertThat(received.headers()).doesNotContainKeys("X-User-Id", "X-Roles", "Cookie", "Forwarded",
                        "X-Forwarded-Host", "X-Forwarded-Proto", "X-Extra");
                String id = response.headers().firstValue("X-Request-Id").orElseThrow();
                assertThat(UUID.fromString(id)).isNotNull();
                assertThat(received.header("X-Request-Id")).isEqualTo(id);
            }
        }
        assertThat(UPSTREAM.calls.get()).isEqualTo(5);
        verify(sessions).isActive(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());
    }

    @Test
    void preservesDownstreamErrorsAndRedirectsWithoutRetriesOrCookieReuse() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (int status : List.of(422, 307, 503)) {
                UPSTREAM.status.set(status);
                int before = UPSTREAM.calls.get();
                var response = request(client, "/api/v1/auth/login", "");
                assertThat(response.statusCode()).isEqualTo(status);
                assertThat(response.body()).isEqualTo("{\"result\":\"unchanged\"}");
                assertThat(UPSTREAM.calls.get()).isEqualTo(before + 1);
                assertThat(UPSTREAM.received.get().header("Cookie")).isNull();
            }
        }
    }

    @Test
    void preservesFormAndMultipartBodiesWithoutTurningThemIntoQueryParameters() throws Exception {
        var bodies = Map.of(
                "application/x-www-form-urlencoded", "name=a%20b&name=c+z&literal=%2f",
                "multipart/form-data; boundary=sample",
                "--sample\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\nvalue\r\n--sample--\r\n");
        try (var client = HttpClient.newHttpClient()) {
            for (var entry : bodies.entrySet()) {
                var outgoing = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                        .header("Content-Type", entry.getKey())
                        .POST(HttpRequest.BodyPublishers.ofString(entry.getValue()))
                        .build();
                assertThat(client.send(outgoing, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
                assertThat(UPSTREAM.received.get().body()).isEqualTo(entry.getValue());
                assertThat(UPSTREAM.received.get().path()).isEqualTo("/api/v1/auth/login");
                assertThat(UPSTREAM.received.get().header("Content-Type")).isEqualTo(entry.getKey());
            }
        }
    }

    private HttpResponse<String> request(HttpClient client, String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-Roles", "ADMIN")
                .POST(HttpRequest.BodyPublishers.noBody());
        if (!token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
