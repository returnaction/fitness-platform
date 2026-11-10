package io.github.returnaction.fitness.identity.security.jwt;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.returnaction.fitness.identity.shared.exception.InvalidAccessTokenException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AccessTokenVerifier {
    private final PublicJwtKeys keys;
    private final String issuer;
    private final String audience;
    private final String keyId;

    public AccessTokenVerifier(PublicJwtKeys keys, @Value("${identity.jwt.issuer}") String issuer,
                               @Value("${identity.jwt.audience}") String audience,
                               @Value("${identity.jwt.key-id}") String keyId) {
        this.keys = keys;
        this.issuer = issuer;
        this.audience = audience;
        this.keyId = keyId;
    }

    public Identity verify(String token) {
        UUID userId;
        UUID sessionId;
        Instant expiresAt;
        try {
            var jwt = SignedJWT.parse(token);
            var header = jwt.getHeader();
            if (!JWSAlgorithm.ES256.equals(header.getAlgorithm()) || !keyId.equals(header.getKeyID()))
                throw invalid();
            var jwk = JWKSet.parse(keys.jwks()).getKeyByKeyId(keyId);
            if (jwk == null)
                throw invalid();
            var key = jwk.toECKey();
            if (key == null)
                throw invalid();
            if (!jwt.verify(new ECDSAVerifier(key)))
                throw invalid();
            var claims = jwt.getJWTClaimsSet();
            Instant now = Instant.now();
            if (!issuer.equals(claims.getIssuer())
                    || claims.getAudience() == null
                    || !claims.getAudience().contains(audience)
                    || claims.getIssueTime() == null
                    || claims.getExpirationTime() == null
                    || claims.getIssueTime().toInstant().isAfter(now)
                    || !claims.getExpirationTime().toInstant().isAfter(now)
                    || claims.getExpirationTime()
                            .toInstant()
                            .isAfter(claims.getIssueTime().toInstant().plusSeconds(900))
                    || (claims.getNotBeforeTime() != null
                            && claims.getNotBeforeTime().toInstant().isAfter(now)))
                throw invalid();
            userId = canonical(claims.getSubject());
            sessionId = canonical(claims.getStringClaim("sid"));
            canonical(claims.getJWTID());
            expiresAt = claims.getExpirationTime().toInstant();
        } catch (java.text.ParseException | com.nimbusds.jose.JOSEException | IllegalArgumentException exception) {
            throw invalid();
        }
        return new Identity(userId, sessionId, expiresAt);
    }

    private static UUID canonical(String value) {
        if (value == null)
            throw invalid();
        UUID result = UUID.fromString(value);
        if (!result.toString().equals(value))
            throw invalid();
        return result;
    }

    private static InvalidAccessTokenException invalid() {
        return new InvalidAccessTokenException();
    }

    public record Identity(UUID userId, UUID sessionId, Instant expiresAt) {
    }
}
