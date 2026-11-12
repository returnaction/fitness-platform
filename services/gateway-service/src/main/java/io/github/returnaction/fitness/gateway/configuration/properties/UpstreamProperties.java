package io.github.returnaction.fitness.gateway.configuration.properties;

import io.github.returnaction.fitness.gateway.routing.UpstreamId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "gateway.upstreams")
public record UpstreamProperties(@NotEmpty Map<UpstreamId, @NotNull @Valid Settings> services) {

    public UpstreamProperties {
        if (services != null) {
            services = Map.copyOf(services);
        }
    }

    public URI baseUrl(UpstreamId upstreamId) {
        Settings settings = services.get(upstreamId);
        if (settings == null) {
            throw new IllegalStateException("Missing upstream configuration: " + upstreamId);
        }
        return settings.baseUrl();
    }

    public Map<UpstreamId, Settings> services() {
        return services == null ? null : Map.copyOf(services);
    }

    public record Settings(@NotNull URI baseUrl) {

        @AssertTrue(message = "Base URL must be an HTTP(S) origin")
        public boolean isBaseUrlValid() {
            if (baseUrl == null) {
                return true;
            }
            String scheme = baseUrl.getScheme();
            String path = baseUrl.getPath();
            return ("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme))
                    && baseUrl.getHost() != null
                    && baseUrl.getUserInfo() == null
                    && baseUrl.getQuery() == null
                    && baseUrl.getFragment() == null
                    && (path == null || path.isEmpty() || "/".equals(path))
                    && (baseUrl.getPort() == -1
                            || (baseUrl.getPort() >= 1 && baseUrl.getPort() <= 65535));
        }
    }
}
