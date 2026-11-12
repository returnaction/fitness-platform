package io.github.returnaction.fitness.gateway.configuration.properties;

import java.time.Duration;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("gateway.security.jwks-startup")
public record JwksStartupProperties(@NotNull Duration attemptTimeout, @NotNull Duration retryDelay) {

    @AssertTrue(message = "JWKS startup durations must be positive and fit in nanoseconds")
    public boolean isDurationsValid() {
        try {
            return attemptTimeout != null && retryDelay != null
                    && attemptTimeout.toNanos() > 0 && retryDelay.toNanos() > 0;
        } catch (ArithmeticException exception) {
            return false;
        }
    }
}
