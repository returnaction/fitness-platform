package io.github.returnaction.fitness.gateway.routing;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.method;
import static org.springframework.web.servlet.function.RequestPredicates.path;

import io.github.returnaction.fitness.gateway.configuration.properties.UpstreamProperties;
import io.github.returnaction.fitness.gateway.request.RequestIdResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration(proxyBeanMethods = false)
public class GatewayRoutesConfiguration {

    @Bean
    RouterFunction<ServerResponse> gatewayRoutes(UpstreamProperties upstreams) {
        var routes = GatewayRouteCatalog.routes();
        GatewayRouteValidator.validate(routes, upstreams);
        return routes.stream()
                .map(definition -> createRoute(definition, upstreams))
                .reduce((left, right) -> left.and(right))
                .orElseThrow(() -> new IllegalStateException("Route catalog must not be empty"));
    }

    private RouterFunction<ServerResponse> createRoute(GatewayRoute definition, UpstreamProperties upstreams) {
        String routeId = definition.method().name() + " " + definition.path();
        return route(routeId).route(method(definition.method()).and(path(definition.path())), http())
                .before(uri(upstreams.baseUrl(definition.upstream()).toString()))
                .after((request, response) -> {
                    response.headers().set("X-Request-Id", RequestIdResolver.getOrCreate(request.servletRequest()));
                    return response;
                })
                .build();
    }

}
