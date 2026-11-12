package io.github.returnaction.fitness.gateway.routing;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.withDefaults;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

@Component
public final class RouteAccessPolicy {

    private final RequestMatcher publicRequests;
    private final RequestMatcher protectedRequests;

    public RouteAccessPolicy(io.github.returnaction.fitness.gateway.configuration.properties.UpstreamProperties upstreams) {
        GatewayRouteValidator.validate(GatewayRouteCatalog.routes(), upstreams);
        publicRequests = matcherFor(RouteAccess.PUBLIC);
        protectedRequests = matcherFor(RouteAccess.AUTHENTICATED);
    }

    public RequestMatcher publicRequests() {
        return publicRequests;
    }

    public RequestMatcher protectedRequests() {
        return protectedRequests;
    }

    public boolean requiresSessionVerification(HttpServletRequest request) {
        return protectedRequests.matches(request);
    }

    private static RequestMatcher matcherFor(RouteAccess access) {
        var paths = withDefaults();
        List<RequestMatcher> matchers = GatewayRouteCatalog.routes()
                .stream()
                .filter(definition -> definition.access() == access)
                .map(definition -> (RequestMatcher) paths.matcher(
                        definition.method(), definition.path()))
                .toList();
        return matchers.isEmpty() ? request -> false : new OrRequestMatcher(matchers);
    }
}
