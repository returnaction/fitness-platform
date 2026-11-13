package io.github.returnaction.fitness.gateway.security.jwt;

import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.github.returnaction.fitness.gateway.configuration.properties.AccessTokenProperties;
import io.github.returnaction.fitness.gateway.security.jwks.JwksInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration(proxyBeanMethods = false)
public class JwtDecoderConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JwtDecoder jwtDecoder(JwksInitializer keys, AccessTokenProperties properties,
        AccessTokenClaimsValidator claimsValidator, Clock clock) {
        var processor = new DefaultJWTProcessor<SecurityContext>();
        processor.setJWSKeySelector((header, context) -> {
            if (!JWSAlgorithm.ES256.equals(header.getAlgorithm()) || header.getKeyID() == null) {
                return List.of();
            }
            for (var key : keys.keys()) {
                if (key.getKeyID().equals(header.getKeyID())) {
                    try {
                        return List.of(key.toECPublicKey());
                    } catch (com.nimbusds.jose.JOSEException exception) {
                        throw new com.nimbusds.jose.KeySourceException("Invalid trusted public key");
                    }
                }
            }
            return List.of();
        });
        // Check required raw claims before Spring's converter can supply defaults.
        // Time validation below uses the shared Clock, not Nimbus's system clock.
        processor.setJWTClaimsSetVerifier((claims, context) -> {
            if (!(claims.getClaim("sub") instanceof String) || !(claims.getClaim("sid") instanceof String)
                    || !(claims.getClaim("jti") instanceof String) || !(claims.getClaim("iat") instanceof Date)
                    || !(claims.getClaim("exp") instanceof Date)) {
                throw new BadJWTException("Missing or invalid required claims");
            }
        });
        var decoder = new NimbusJwtDecoder(processor);
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(properties.issuer()),
                        new JwtAudienceValidator(properties.audience()), claimsValidator));
        return decoder;
    }
}
