package io.github.returnaction.fitness.identity.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.github.returnaction.fitness.identity.registration.entity.role.RoleCode;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessTokenSignerTest {

    @Test
    void signsRequiredClaimsAndNeverOutlivesSession() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var signer = new AccessTokenSigner(
                (ECPrivateKey) pair.getPrivate(), "key-1", "issuer", "gateway", Clock.fixed(now, ZoneOffset.UTC));
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        var jwt = SignedJWT
                .parse(signer.sign(userId, sessionId, now.plusSeconds(300), java.util.List.of(RoleCode.USER)));
        assertThat(jwt.verify(new ECDSAVerifier((ECPublicKey) pair.getPublic())))
                .isTrue();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("key-1");
        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getStringListClaim("roles")).containsExactly("USER");
        assertThat(claims.getIssuer()).isEqualTo("issuer");
        assertThat(claims.getAudience()).containsExactly("gateway");
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.getStringClaim("sid")).isEqualTo(sessionId.toString());
        assertThat(UUID.fromString(claims.getJWTID())).isNotNull();
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(now);
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(now.plusSeconds(300));
        var adminOnly = SignedJWT
                .parse(signer.sign(userId, sessionId, now.plusSeconds(300), java.util.List.of(RoleCode.ADMIN)));
        assertThat(adminOnly.getJWTClaimsSet().getStringListClaim("roles")).containsExactly("ADMIN");
        var multiple = SignedJWT.parse(
                signer.sign(userId, sessionId, now.plusSeconds(300), java.util.List.of(RoleCode.USER, RoleCode.ADMIN)));
        assertThat(multiple.getJWTClaimsSet().getStringListClaim("roles")).containsExactly("USER", "ADMIN");
        assertThatThrownBy(() -> signer.sign(userId, sessionId, now.plusSeconds(300), java.util.List.of()))
                .isInstanceOf(IllegalStateException.class);
        var longSession = SignedJWT
                .parse(signer.sign(userId, sessionId, now.plusSeconds(3600), java.util.List.of(RoleCode.USER)));
        assertThat(longSession.getJWTClaimsSet().getExpirationTime().toInstant())
                .isEqualTo(now.plusSeconds(900));
        assertThatThrownBy(() -> signer.sign(userId, sessionId, now, java.util.List.of(RoleCode.USER)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
