package io.github.returnaction.fitness.gateway;

import io.github.returnaction.fitness.gateway.routing.RouteValidationFilter;
import io.github.returnaction.fitness.gateway.routing.RouteAccess;
import io.github.returnaction.fitness.gateway.security.session.SessionVerificationClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import io.github.returnaction.fitness.gateway.configuration.properties.UpstreamProperties;
import io.github.returnaction.fitness.gateway.routing.GatewayRoute;
import io.github.returnaction.fitness.gateway.routing.GatewayRouteCatalog;
import io.github.returnaction.fitness.gateway.routing.GatewayRouteValidator;
import io.github.returnaction.fitness.gateway.routing.RouteAccessPolicy;
import io.github.returnaction.fitness.gateway.routing.UpstreamId;
import io.github.returnaction.fitness.gateway.security.jwks.JwksInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

/**
 * Проверяет валидацию маршрутов и policy доступа Gateway.
 *
 * <p>Spec 017 T003.3: ожидания каталога расширены для
 * {@code GET /api/v1/coins/me}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RouteValidationTest {

    private static final String CHALLENGE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String OPERATION_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    private static final List<GatewayRoute> EXPECTED_ROUTES = List.of(
            new GatewayRoute(HttpMethod.POST, "/api/v1/auth/register", UpstreamId.IDENTITY, RouteAccess.PUBLIC),
            new GatewayRoute(HttpMethod.POST, "/api/v1/auth/login", UpstreamId.IDENTITY, RouteAccess.PUBLIC),
            new GatewayRoute(HttpMethod.POST, "/api/v1/auth/refresh", UpstreamId.IDENTITY, RouteAccess.PUBLIC),
            new GatewayRoute(HttpMethod.POST, "/api/v1/auth/logout", UpstreamId.IDENTITY, RouteAccess.PUBLIC),
            new GatewayRoute(HttpMethod.POST, "/api/v1/auth/logout-all", UpstreamId.IDENTITY, RouteAccess.AUTHENTICATED),
            new GatewayRoute(HttpMethod.GET, "/api/v1/accounts/me", UpstreamId.ACCOUNT, RouteAccess.AUTHENTICATED),
            new GatewayRoute(HttpMethod.PUT, "/api/v1/accounts/me", UpstreamId.ACCOUNT, RouteAccess.AUTHENTICATED),
            new GatewayRoute(HttpMethod.GET, "/api/v1/coins/me", UpstreamId.COINS, RouteAccess.AUTHENTICATED),
            new GatewayRoute(HttpMethod.POST, "/api/v1/challenges/drafts", UpstreamId.CHALLENGE,
                    RouteAccess.AUTHENTICATED),
            new GatewayRoute(HttpMethod.GET, "/api/v1/challenges/{challengeId}", UpstreamId.CHALLENGE,
                    RouteAccess.AUTHENTICATED),
            new GatewayRoute(HttpMethod.PATCH, "/api/v1/challenges/{challengeId}/draft", UpstreamId.CHALLENGE,
                    RouteAccess.AUTHENTICATED),
            new GatewayRoute(HttpMethod.POST, "/api/v1/challenges/{challengeId}/publish", UpstreamId.CHALLENGE,
                    RouteAccess.AUTHENTICATED),
            new GatewayRoute(HttpMethod.POST, "/api/v1/challenges/{challengeId}/joins", UpstreamId.CHALLENGE,
                    RouteAccess.AUTHENTICATED),
            new GatewayRoute(HttpMethod.POST, "/api/v1/challenges/{challengeId}/cancel", UpstreamId.CHALLENGE,
                    RouteAccess.AUTHENTICATED),
            new GatewayRoute(HttpMethod.GET, "/api/v1/challenge-operations/{operationId}", UpstreamId.CHALLENGE,
                    RouteAccess.AUTHENTICATED),
            new GatewayRoute(HttpMethod.GET, "/api/v1/challenges/{challengeId}/result", UpstreamId.CHALLENGE,
                    RouteAccess.AUTHENTICATED));

    @MockitoBean
    private SessionVerificationClient sessions;

    @MockitoBean
    private JwksInitializer jwks;
    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private RouteAccessPolicy policy;
    @Autowired
    private ServletContext servletContext;

    @BeforeEach
    void ready() {
        Mockito.when(jwks.ready()).thenReturn(true);
    }

    @Test
    void rejectsPathThenMethodThenQueryRegardlessOfBearer() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String bearer : List.of("", "Bearer invalid", "Bearer expired-token")) {
                assertError(client, "POST", "/unknown?x=1", bearer, 404, "not_found");
                assertError(client, "OPTIONS", "/unknown", bearer, 404, "not_found");
                for (var route : GatewayRouteCatalog.routes()) {
                    String path = concretePath(route.path());
                    assertError(client, "DELETE", path + "?x=1", bearer, 405, "method_not_allowed");
                    assertError(client, "OPTIONS", path, bearer, 405, "method_not_allowed");
                    assertError(client, "HEAD", path, bearer, 405, null);
                    assertError(client, route.method().name(), path + "?x=1", bearer, 400, "invalid_request");
                }
            }
        }
    }

    @Test
    void rejectsEmptyQuerySentWithoutClientUriNormalization() throws Exception {
        for (var route : GatewayRouteCatalog.routes()) {
            try (var socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream()
                        .write((route.method().name() + " " + concretePath(route.path()) + "? HTTP/1.1\r\n"
                                + "Host: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(response).startsWith("HTTP/1.1 400").contains("invalid_request");
            }
        }
    }

    @Autowired
    private RouteValidationFilter validation;

    @Test
    void rejectedRequestsNeverInvokeNextFilter() throws Exception {
        for (var request : List.of(new MockHttpServletRequest("POST", "/unknown"),
                new MockHttpServletRequest("GET", "/api/v1/auth/login"),
                new MockHttpServletRequest("POST", "/api/v1/auth/login"))) {
            request.setQueryString("token=untrusted");
            var chain = Mockito.mock(FilterChain.class);
            validation.doFilter(request, new MockHttpServletResponse(), chain);
            Mockito.verifyNoInteractions(chain);
        }
    }

    @Test
    void policyMatchesOnlyCatalogOperationsAndProtectedRouteStaysClosed() throws Exception {
        assertThat(GatewayRouteCatalog.routes()).containsExactlyElementsOf(EXPECTED_ROUTES);
        try (var client = HttpClient.newHttpClient()) {
            for (var route : GatewayRouteCatalog.routes()) {
                String path = concretePath(route.path());
                var request = new MockHttpServletRequest(route.method().name(), path);
                boolean protectedRoute = route.access() == RouteAccess.AUTHENTICATED;
                assertThat(policy.protectedRequests().matches(request)).isEqualTo(protectedRoute);
                assertThat(policy.publicRequests().matches(request)).isEqualTo(!protectedRoute);
                if (protectedRoute) {
                    assertError(client, route.method().name(), path, "", 401, "invalid_access_token");
                }
            }
        }
        assertThat(policy.publicRequests().matches(new MockHttpServletRequest("GET", "/api/v1/auth/login"))).isFalse();
        assertThat(servletContext.getFilterRegistrations()
                .values()
                .stream()
                .filter(registration -> registration.getClassName().endsWith(".RouteValidationFilter"))
                .count()).isZero();
    }

    @Test
    void catalogValidationRejectsEmptyDuplicateAndMissingUpstream() {
        var upstreams = new UpstreamProperties(
                Map.of(UpstreamId.IDENTITY, new UpstreamProperties.Settings(URI.create("http://localhost:1")),
                        UpstreamId.ACCOUNT, new UpstreamProperties.Settings(URI.create("http://localhost:2")),
                        UpstreamId.COINS, new UpstreamProperties.Settings(URI.create("http://localhost:3")),
                        UpstreamId.CHALLENGE, new UpstreamProperties.Settings(URI.create("http://localhost:4"))));
        var routes = GatewayRouteCatalog.routes();
        GatewayRouteValidator.validate(routes, upstreams);
        var missingAccountUpstreams = new UpstreamProperties(Map.of(UpstreamId.IDENTITY,
                new UpstreamProperties.Settings(URI.create("http://localhost:1"))));
        assertThatThrownBy(() -> GatewayRouteValidator.validate(routes, missingAccountUpstreams))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCOUNT");
        var emptyRoutes = List.<GatewayRoute>of();
        assertThatThrownBy(() -> GatewayRouteValidator.validate(emptyRoutes, upstreams))
                .isInstanceOf(IllegalStateException.class);
        var duplicatedRoutes = List.of(routes.getFirst(), routes.getFirst());
        assertThatThrownBy(() -> GatewayRouteValidator.validate(duplicatedRoutes, upstreams))
                .isInstanceOf(IllegalStateException.class);
        var emptyUpstreams = new UpstreamProperties(Map.of());
        assertThatThrownBy(() -> GatewayRouteValidator.validate(routes, emptyUpstreams))
                .isInstanceOf(IllegalStateException.class);
    }

    private static String concretePath(String path) {
        return path.replace("{challengeId}", CHALLENGE_ID)
                .replace("{operationId}", OPERATION_ID);
    }

    private static String allowedMethods(String path) {
        int query = path.indexOf('?');
        String pathOnly = query < 0 ? path : path.substring(0, query);
        var parser = new PathPatternParser();
        var parsed = PathContainer.parsePath(pathOnly);
        return GatewayRouteCatalog.routes().stream()
                .filter(route -> parser.parse(route.path()).matches(parsed))
                .map(route -> route.method().name())
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private void assertError(HttpClient client, String method, String path, String bearer, int status,
        String code) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Origin", "https://untrusted.example")
                .header("X-Request-Id", "client-id")
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (!bearer.isEmpty()) {
            builder.header("Authorization", bearer);
        }
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method + " " + path).isEqualTo(status);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods")).isEmpty();
        if (status == 405) {
            assertThat(response.headers().firstValue("Allow")).contains(allowedMethods(path));
        }
        String id = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(UUID.fromString(id)).isNotNull();
        if (code != null) {
            var error = mapper.readTree(response.body()).get("error");
            assertThat(error.get("code").asString()).isEqualTo(code);
            assertThat(error.get("requestId").asString()).isEqualTo(id);
        }
    }
}
