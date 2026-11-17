package io.github.returnaction.fitness.challenge.coins;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "challenge.coins.grpc")
public record CoinsStakeProperties(@NotBlank String host,
                                   @Min(1) @Max(65535) int port,
                                   @NotBlank String certificate,
                                   @NotBlank String privateKey,
                                   @NotBlank String trustCertificate,
                                   @NotNull Duration deadline) {

    public CoinsStakeProperties {
        if (deadline != null && (deadline.isZero() || deadline.isNegative()
                || deadline.compareTo(Duration.ofSeconds(7)) >= 0)) {
            throw new IllegalArgumentException("Coins gRPC deadline must be positive and less than 7 seconds");
        }
    }
}
