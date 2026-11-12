package io.github.returnaction.fitness.gateway.routing;

import static io.github.returnaction.fitness.gateway.routing.RouteAccess.AUTHENTICATED;
import static io.github.returnaction.fitness.gateway.routing.RouteAccess.PUBLIC;
import static io.github.returnaction.fitness.gateway.routing.UpstreamId.IDENTITY;
import static org.springframework.http.HttpMethod.POST;

import java.util.List;

public final class GatewayRouteCatalog {

    private static final List<GatewayRoute> ROUTES = List.of(
            new GatewayRoute(POST, "/api/v1/auth/register", IDENTITY, PUBLIC),
            new GatewayRoute(POST, "/api/v1/auth/login", IDENTITY, PUBLIC),
            new GatewayRoute(POST, "/api/v1/auth/refresh", IDENTITY, PUBLIC),
            new GatewayRoute(POST, "/api/v1/auth/logout", IDENTITY, PUBLIC),
            new GatewayRoute(POST, "/api/v1/auth/logout-all", IDENTITY, AUTHENTICATED));

    private GatewayRouteCatalog() {
    }

    public static List<GatewayRoute> routes() {
        return ROUTES;
    }
}
