package io.github.returnaction.fitness.gateway;

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

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private io.github.returnaction.fitness.gateway.security.session.SessionVerificationClient sessions;

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
        when(sessions.isActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
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
        for (Object roles : List.of("USER", List.of(1), java.util.Arrays.asList("USER", null))) {
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
                    // Temporary denyAll remains until forwarding is implemented.
                    assertThat(request(client, route.path(), token).statusCode()).isEqualTo(401);
                }
            }
            assertThat(request(client, "/unknown", "bad").statusCode()).isEqualTo(404);
            assertThat(request(client, "/api/v1/auth/logout-all?x=1", "bad").statusCode()).isEqualTo(400);
        }
        verify(decoder, never()).decode(org.mockito.ArgumentMatchers.anyString());
        verify(sessions, never()).isActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void protectedRouteChecksSessionOnlyAfterValidJwtAndStaysClosedUntilForwarding() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var invalid = request(client, "/api/v1/auth/logout-all", sign(claims(), KEY, "unknown"));
            assertThat(invalid.statusCode()).isEqualTo(401);
            assertThat(invalid.body()).contains("invalid_access_token", "request_id");
            assertThat(invalid.headers().firstValue("WWW-Authenticate")).contains("Bearer");
            org.mockito.Mockito.verifyNoInteractions(sessions);
            var valid = request(client, "/api/v1/auth/logout-all", sign(claims()));
            assertThat(valid.statusCode()).isEqualTo(403);
            assertThat(valid.body()).contains("access_denied");
            assertThat(request(client, "/api/v1/auth/logout-all", "").statusCode()).isEqualTo(401);
        }
    }

    @Test
    void sessionInvalidOrUnavailableIsRejectedWithSameRequestId() throws Exception {
        var claims = claims();
        var user = UUID.fromString((String) claims.get("sub"));
        var session = UUID.fromString((String) claims.get("sid"));
        var requestId = new java.util.concurrent.atomic.AtomicReference<UUID>();
        when(sessions.isActive(org.mockito.ArgumentMatchers.eq(user), org.mockito.ArgumentMatchers.eq(session),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                    requestId.set(invocation.getArgument(2));
                    io.github.returnaction.fitness.gateway.timeout.RequestDeadline deadline = invocation.getArgument(3);
                    assertThat(deadline.remainingNanos()).isPositive().isLessThanOrEqualTo(3_000_000_000L);
                    return false;
                });
        try (var client = HttpClient.newHttpClient()) {
            var invalid = request(client, "/api/v1/auth/logout-all", sign(claims));
            assertThat(invalid.statusCode()).isEqualTo(401);
            assertThat(invalid.headers().firstValue("X-Request-Id")).contains(requestId.get().toString());
            assertThat(invalid.body()).contains(requestId.get().toString(), "invalid_access_token");
            when(sessions.isActive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenThrow(
                            new io.github.returnaction.fitness.gateway.security.session.SessionVerificationUnavailableException(
                                    "private transport diagnostics"));
            var failed = request(client, "/api/v1/auth/logout-all", sign(claims));
            assertThat(failed.statusCode()).isEqualTo(503);
            assertThat(failed.body()).contains("identity_unavailable").doesNotContain("private transport diagnostics");
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
