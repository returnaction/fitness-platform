package io.github.returnaction.fitness.gateway.configuration.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "gateway.security.session-verification")
public record SessionVerificationProperties(
        @NotBlank String host,
        @Min(1) @Max(65535) int port,
        @NotNull Path trustCertificate,
        @NotNull Path certificate,
        @NotNull Path privateKey,
        @NotNull Duration shutdownTimeout) {

    @AssertTrue(message = "Shutdown timeout must be at least one millisecond "
            + "and fit in milliseconds")
    public boolean isShutdownTimeoutValid() {
        if (shutdownTimeout == null) {
            return true;
        }
        try {
            return shutdownTimeout.toMillis() > 0;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

}
