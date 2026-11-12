package io.github.returnaction.fitness.gateway.configuration.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

@Validated
@ConfigurationProperties(prefix = "gateway.security.access-token")
public record AccessTokenProperties(@NotNull URI jwksUri,
                                    @NotBlank String issuer,
                                    @NotBlank String audience) {

    @AssertTrue(message = "JWKS URI must be an absolute HTTP(S) address without user information or a fragment")
    public boolean isJwksUriValid() {
        if (jwksUri == null) {
            return true;
        }
        boolean supportedScheme = "https".equalsIgnoreCase(jwksUri.getScheme())
            || "http".equalsIgnoreCase(jwksUri.getScheme());
        return supportedScheme
            && jwksUri.getHost() != null
            && jwksUri.getUserInfo() == null
            && jwksUri.getFragment() == null;
    }
}
