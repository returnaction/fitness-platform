package io.github.returnaction.fitness.gateway.configuration.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "gateway.request")
public record RequestTimeoutProperties(@NotNull Duration timeout) {

    @AssertTrue(message = "Request timeout must be positive and fit in nanoseconds")
    public boolean isTimeoutValid() {
        if (timeout == null) {
            return true; // Отсутствие проверяет @NotNull.
        }
        try {
            return timeout.toNanos() > 0;
        } catch (ArithmeticException exception) {
            return false;
        }
    }
}
