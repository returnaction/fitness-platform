package io.github.returnaction.fitness.gateway.security;

import io.github.returnaction.fitness.gateway.exception.GatewayException;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jwt.SignedJWT;
import io.github.returnaction.fitness.gateway.configuration.GatewayProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public final class AccessTokens {

    private final JwksKeys keys;
    private final GatewayProperties settings;

    public AccessTokens(JwksKeys keys, GatewayProperties settings) {
        this.keys = keys;
        this.settings = settings;
    }

    public Principal verify(String token) {
        try {
            var jwt = SignedJWT.parse(token);
            var header = jwt.getHeader();
            if (!JWSAlgorithm.ES256.equals(header.getAlgorithm()) || header.getKeyID() == null
                    || header.getKeyID().isBlank() || header.getKeyID().length() > 128
                    || header.getCriticalParams() != null)
                throw GatewayException.invalid();
            var key = keys.get(header.getKeyID());
            if (!Curve.P_256.equals(key.getCurve()) || !jwt.verify(new ECDSAVerifier(key))) {
                throw GatewayException.invalid();
            }
            var claims = jwt.getJWTClaimsSet();
            Instant now = Instant.now();
            if (!settings.issuer().equals(claims.getIssuer()) || !claims.getAudience().contains(settings.audience())
                    || claims.getIssueTime() == null || claims.getExpirationTime() == null
                    || claims.getIssueTime().toInstant().isAfter(now)
                    || !claims.getExpirationTime().toInstant().isAfter(now)
                    || !claims.getExpirationTime().after(claims.getIssueTime())
                    || claims.getExpirationTime()
                            .toInstant()
                            .isAfter(claims.getIssueTime().toInstant().plusSeconds(900))
                    || (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(now))) {
                throw GatewayException.invalid();
            }
            var roles = claims.getStringListClaim("roles");
            // This route requires authentication, not a role. Unknown/missing roles grant
            // no authority.
            if (roles != null && roles.stream().anyMatch(java.util.Objects::isNull))
                throw GatewayException.invalid();
            canonical(claims.getJWTID());
            return new Principal(canonical(claims.getSubject()), canonical(claims.getStringClaim("sid")));
        } catch (GatewayException failure) {
            throw failure;
        } catch (java.text.ParseException | com.nimbusds.jose.JOSEException | IllegalArgumentException exception) {
            throw GatewayException.invalid();
        }
    }

    private static UUID canonical(String value) {
        if (value == null)
            throw GatewayException.invalid();
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value))
            throw GatewayException.invalid();
        return id;
    }

    public record Principal(UUID userId, UUID sessionId) {
    }
}
