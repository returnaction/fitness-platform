package io.github.returnaction.fitness.gateway.security;

import io.github.returnaction.fitness.gateway.exception.GatewayException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.github.returnaction.fitness.gateway.configuration.GatewayProperties;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SecurityPolicyTest {
    static GatewayProperties settings(URI keys) {
        return new GatewayProperties(URI.create("http://localhost:1"), keys, "issuer", "audience",
                16384, 2, 60, 2, 300, 5, 500, "localhost", 1,
                "missing.crt", "missing.key", "missing-ca.crt", 500);
    }

    @Test
    void rateLimitIsConcurrentBoundedAndExpires() throws Exception {
        var clock = new MutableClock();
        var limits = new RequestRateLimiter(settings(URI.create("http://localhost:1")), clock);
        var accepted = new AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 100; i++)
                executor.submit(() -> {
                    try {
                        limits.check("one");
                        accepted.incrementAndGet();
                    } catch (GatewayException ignored) {
                    }
                });
        }
        assertThat(accepted).hasValue(2);
        limits.check("two");
        assertThatThrownBy(() -> limits.check("three")).isInstanceOf(GatewayException.class);
        clock.now = clock.now.plusSeconds(61);
        limits.check("three");
    }

    @Test
    void jwksRotationRefreshThrottleAndOutage() throws Exception {
        var clock = new MutableClock();
        ECKey first = new ECKeyGenerator(Curve.P_256).keyID("first").generate();
        ECKey second = new ECKeyGenerator(Curve.P_256).keyID("second").generate();
        var document = new AtomicReference<>(new JWKSet(first.toPublicJWK()).toString());
        var calls = new AtomicInteger();
        var status = new AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] bytes = document.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (var keys = new JwksKeyCache(settings(URI.create("http://127.0.0.1:" + server.getAddress().getPort())),
                clock)) {
            assertThat(keys.get("first").getKeyID()).isEqualTo("first");
            for (int i = 0; i < 10; i++)
                assertThatThrownBy(() -> keys.get("unknown")).hasMessage("invalid_access_token");
            assertThat(calls).hasValue(1);
            document.set(new JWKSet(List.of(first.toPublicJWK(), second.toPublicJWK())).toString());
            clock.now = clock.now.plusSeconds(6);
            assertThat(keys.get("second").getKeyID()).isEqualTo("second");
            assertThat(keys.get("first").getKeyID()).isEqualTo("first");
            status.set(503);
            clock.now = clock.now.plusSeconds(301);
            assertThatThrownBy(() -> keys.get("first")).hasMessage("identity_unavailable");
            assertThatThrownBy(() -> keys.get("first")).hasMessage("identity_unavailable");
            assertThat(calls).hasValue(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void jwtRejectsInvalidClaimsAndSignatures() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).keyID("test").generate();
        var keys = org.mockito.Mockito.mock(JwksKeyCache.class);
        org.mockito.Mockito.when(keys.get("test")).thenReturn(key.toPublicJWK());
        var verifier = new AccessTokenVerifier(keys, settings(URI.create("http://localhost:1")));
        Instant now = Instant.now();
        var valid = claims(now);
        assertThat(verifier.verify(sign(key, valid.build())).userId()).isNotNull();
        for (var invalid : List.of(claims(now).audience("other"), claims(now).issuer("other"),
                claims(now).subject("123"), claims(now).claim("sid", "bad"), claims(now).jwtID(null),
                claims(now).claim("roles", "USER"), claims(now).claim("roles", List.of(123)),
                claims(now).issueTime(Date.from(now.plusSeconds(10))),
                claims(now).expirationTime(Date.from(now.plusSeconds(901))),
                claims(now).notBeforeTime(Date.from(now.plusSeconds(10))))) {
            String token = sign(key, invalid.build());
            assertThatThrownBy(() -> verifier.verify(token)).hasMessage("invalid_access_token");
        }
        ECKey attacker = new ECKeyGenerator(Curve.P_256).keyID("test").generate();
        String forged = sign(attacker, claims(now).build());
        assertThatThrownBy(() -> verifier.verify(forged)).hasMessage("invalid_access_token");
    }

    @Test
    void missingSettingsAndCertificatesFailStartup() {
        new ApplicationContextRunner().withUserConfiguration(SettingsConfiguration.class)
                .run(context -> assertThat(context).hasFailed());
        assertThatThrownBy(() -> new SessionVerificationClient(settings(URI.create("http://localhost:1")),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry())).isInstanceOf(Exception.class);
    }

    private JWTClaimsSet.Builder claims(Instant now) {
        return new JWTClaimsSet.Builder().issuer("issuer")
                .audience("audience")
                .subject(UUID.randomUUID().toString())
                .claim("sid", UUID.randomUUID().toString())
                .jwtID(UUID.randomUUID().toString())
                .claim("roles", List.of("USER"))
                .issueTime(Date.from(now.minusSeconds(1)))
                .expirationTime(Date.from(now.plusSeconds(60)));
    }

    private String sign(ECKey key, JWTClaimsSet claims) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("test").build(), claims);
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GatewayProperties.class)
    static class SettingsConfiguration {
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.now();
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
        @Override
        public Instant instant() {
            return now;
        }
    }
}
