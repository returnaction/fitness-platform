package io.github.returnaction.fitness.gateway.routing;

import java.util.HashSet;
import java.util.List;
import io.github.returnaction.fitness.gateway.configuration.properties.UpstreamProperties;

public final class GatewayRouteValidator {

    private GatewayRouteValidator() {
    }

    public static void validate(List<GatewayRoute> routes, UpstreamProperties upstreams) {
        if (routes.isEmpty()) {
            throw new IllegalStateException("Route catalog must not be empty");
        }
        var operations = new HashSet<String>();
        for (var route : routes) {
            if (!operations.add(route.method().name() + " " + route.path())) {
                throw new IllegalStateException("Duplicate route operation");
            }
            upstreams.baseUrl(route.upstream());
        }
    }
}
