package io.github.returnaction.fitness.gateway.configuration;

import io.github.returnaction.fitness.gateway.configuration.properties.AccessTokenProperties;
import io.github.returnaction.fitness.gateway.configuration.properties.UpstreamProperties;
import io.github.returnaction.fitness.gateway.routing.UpstreamId;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("production")
public final class ProductionConfiguration {

    public ProductionConfiguration(AccessTokenProperties accessToken, UpstreamProperties upstreams) {
        if (!"https".equalsIgnoreCase(accessToken.jwksUri().getScheme())
                || !"https".equalsIgnoreCase(upstreams.baseUrl(UpstreamId.IDENTITY).getScheme())) {
            throw new IllegalStateException("Production Identity and JWKS endpoints must use HTTPS");
        }
    }
}
