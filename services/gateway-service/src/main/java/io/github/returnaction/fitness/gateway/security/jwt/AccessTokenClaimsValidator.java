package io.github.returnaction.fitness.gateway.security.jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class AccessTokenClaimsValidator implements OAuth2TokenValidator<Jwt> {

    private static final Duration MAX_TOKEN_LIFETIME = Duration.ofMinutes(15);
    private static final OAuth2Error INVALID_TOKEN = new OAuth2Error(
            "invalid_token",
            "Access token claims do not satisfy the token contract", null);
    private final Clock clock;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        var claims = token.getClaims();
        if (!isCanonicalUuid(claims.get("sub"))
                || !isCanonicalUuid(claims.get("sid"))
                || !isCanonicalUuid(claims.get("jti"))) {
            return invalidToken();
        }
        if (!(claims.get("iat") instanceof Instant issuedAt)
                || !(claims.get("exp") instanceof Instant expiresAt)) {
            return invalidToken();
        }
        Instant now = clock.instant();
        if (issuedAt.isAfter(now)) {
            return invalidToken();
        }
        if (!expiresAt.isAfter(issuedAt) || !expiresAt.isAfter(now)) {
            return invalidToken();
        }
        Duration lifetime = Duration.between(issuedAt, expiresAt);
        if (lifetime.compareTo(MAX_TOKEN_LIFETIME) > 0) {
            return invalidToken();
        }
        if (!hasValidRolesFormat(claims.get("roles"))) {
            return invalidToken();
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static boolean isCanonicalUuid(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        try {
            UUID id = UUID.fromString(text);
            return id.toString().equals(text);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean hasValidRolesFormat(Object value) {
        if (value == null) {
            return true;
        }
        if (!(value instanceof List<?> roles)) {
            return false;
        }
        return roles.stream().allMatch(role -> role instanceof String);
    }

    private static OAuth2TokenValidatorResult invalidToken() {
        return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
    }
}
