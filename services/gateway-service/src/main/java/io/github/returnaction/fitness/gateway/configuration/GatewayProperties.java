package io.github.returnaction.fitness.gateway.configuration;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

@Validated
@ConfigurationProperties("gateway")
public record GatewayProperties(@NotNull URI identityUrl,
                                @NotNull URI jwksUrl,
                                @NotBlank String issuer,
                                @NotBlank String audience,
                                @Min(1) int maxBodyBytes,
                                @Min(1) int rateCapacity,
                                @Min(1) int ratePeriodSeconds,
                                @Min(1) int maxClients,
                                @Min(1) int jwksTtlSeconds,
                                @Min(1) int jwksRefreshSeconds,
                                @Min(1) int httpTimeoutMillis,
                                @NotBlank String sessionHost,
                                @Min(1) int sessionPort,
                                @NotBlank String certificate,
                                @NotBlank String privateKey,
                                @NotBlank String trustCertificate,
                                @Min(1) int sessionTimeoutMillis) {
    public GatewayProperties {
        validateUri(identityUrl);
        validateUri(jwksUrl);
    }

    private static void validateUri(URI uri) {
        if (uri != null && (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
            || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
            || uri.getQuery() != null)) {
            throw new IllegalArgumentException("Invalid upstream URI configuration");
        }
    }
}
