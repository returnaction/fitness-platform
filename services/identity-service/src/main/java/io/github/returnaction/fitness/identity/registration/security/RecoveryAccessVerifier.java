package io.github.returnaction.fitness.identity.registration.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.returnaction.fitness.identity.registration.exception.RecoveryAccessException;
import io.github.returnaction.fitness.identity.registration.repository.RecoveryAccessRepository;
import io.github.returnaction.fitness.identity.security.jwt.PublicJwtKeys;
import io.github.returnaction.fitness.identity.session.repository.SessionVerificationRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Проверяет токен, действующую сессию и актуальную роль на primary Identity.
 */
@Component
@ConditionalOnProperty(name = "identity.jwt.enabled", havingValue = "true")
public class RecoveryAccessVerifier {
    private final PublicJwtKeys keys;
    private final SessionVerificationRepository sessions;
    private final RecoveryAccessRepository repository;
    private final String issuer;
    private final String audience;
    private final String keyId;

    public RecoveryAccessVerifier(
                                  PublicJwtKeys keys,
                                  SessionVerificationRepository sessions,
                                  RecoveryAccessRepository repository,
                                  @Value("${identity.jwt.issuer}") String issuer,
                                  @Value("${identity.jwt.audience}") String audience,
                                  @Value("${identity.jwt.key-id}") String keyId) {
        this.keys = keys;
        this.sessions = sessions;
        this.repository = repository;
        this.issuer = issuer;
        this.audience = audience;
        this.keyId = keyId;
    }

    public UUID verify(String token) {
        UUID userId;
        UUID sessionId;
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
        } catch (java.text.ParseException | com.nimbusds.jose.JOSEException | IllegalArgumentException exception) {
            throw invalid();
        }
        if (!sessions.check(userId, sessionId).active())
            throw invalid();
        boolean authorized = repository.isActiveSuperAdmin(userId);
        if (!authorized)
            throw new RecoveryAccessException("forbidden");
        return userId;
    }

    private static UUID canonical(String value) {
        if (value == null)
            throw invalid();
        UUID result = UUID.fromString(value);
        if (!result.toString().equals(value))
            throw invalid();
        return result;
    }

    private static RecoveryAccessException invalid() {
        return new RecoveryAccessException("invalid_access_token");
    }
}
