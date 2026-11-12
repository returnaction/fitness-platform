package io.github.returnaction.fitness.gateway.routing;

import java.util.Objects;
import org.springframework.http.HttpMethod;

public record GatewayRoute(HttpMethod method, String path, UpstreamId upstream, RouteAccess access) {

    public GatewayRoute {
        Objects.requireNonNull(method, "Route method is required");
        Objects.requireNonNull(path, "Route path is required");
        Objects.requireNonNull(upstream, "Route upstream is required");
        Objects.requireNonNull(access, "Route access is required");
        if (path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("Route path must start with a slash");
        }
    }
}
