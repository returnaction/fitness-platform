package io.github.returnaction.fitness.identity.security.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.returnaction.fitness.identity.registration.entity.role.RoleCode;
import java.security.interfaces.ECPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Подписывает короткоживущие access JWT; ключ и параметры поступают из среды.
 */
public final class AccessTokenSigner {

    private final ECPrivateKey key;
    private final String keyId;
    private final String issuer;
    private final String audience;
    private final Clock clock;

    public AccessTokenSigner(ECPrivateKey key, String keyId, String issuer, String audience, Clock clock) {
        this.key = Objects.requireNonNull(key);
        if (!Curve.P_256.equals(Curve.forECParameterSpec(key.getParams()))
                || keyId == null
                || keyId.isBlank()
                || issuer == null
                || issuer.isBlank()
                || audience == null
                || audience.isBlank()) {
            throw new IllegalArgumentException("Invalid access token signing configuration");
        }
        this.keyId = keyId;
        this.issuer = issuer;
        this.audience = audience;
        this.clock = Objects.requireNonNull(clock);
    }

    public String sign(UUID userId, UUID sessionId, Instant sessionExpiresAt, List<RoleCode> roles) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(sessionId);
        if (roles == null || roles.isEmpty() || roles.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("Access token requires assigned roles");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = Objects.requireNonNull(sessionExpiresAt).truncatedTo(ChronoUnit.SECONDS);
        if (expires.isAfter(now.plusSeconds(900))) {
            expires = now.plusSeconds(900);
        }
        if (!expires.isAfter(now)) {
            throw new IllegalArgumentException("Session has expired");
        }
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(userId.toString())
                .claim("sid", sessionId.toString())
                .claim("roles", roles.stream().map(RoleCode::name).toList())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expires))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(), claims);
        try {
            jwt.sign(new ECDSASigner(key));
        } catch (JOSEException exception) {
            throw new IllegalStateException("Access token signing failed", exception);
        }
        return jwt.serialize();
    }
}
