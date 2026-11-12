package io.github.returnaction.fitness.gateway;

import io.github.returnaction.fitness.gateway.routing.RouteValidationFilter;
import io.github.returnaction.fitness.gateway.security.session.SessionVerificationClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.mockito.Mockito;
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
import io.github.returnaction.fitness.gateway.configuration.properties.UpstreamProperties;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RouteValidationTest {

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
                    assertError(client, "GET", route.path() + "?x=1", bearer, 405, "method_not_allowed");
                    assertError(client, "OPTIONS", route.path(), bearer, 405, "method_not_allowed");
                    assertError(client, "HEAD", route.path(), bearer, 405, null);
                    assertError(client, "POST", route.path() + "?x=1", bearer, 400, "invalid_request");
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
                        .write(("POST " + route.path() + "? HTTP/1.1\r\n"
                                + "Host: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                String response = new String(socket.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
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
        assertThat(GatewayRouteCatalog.routes()).hasSize(5);
        try (var client = HttpClient.newHttpClient()) {
            for (var route : GatewayRouteCatalog.routes()) {
                var request = new MockHttpServletRequest("POST", route.path());
                boolean protectedRoute = route.path().endsWith("/logout-all");
                assertThat(policy.protectedRequests().matches(request)).isEqualTo(protectedRoute);
                assertThat(policy.publicRequests().matches(request)).isEqualTo(!protectedRoute);
                if (protectedRoute) {
                    assertError(client, "POST", route.path(), "", 401, "invalid_access_token");
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
        var upstreams = new UpstreamProperties(Map.of(UpstreamId.IDENTITY,
                new UpstreamProperties.Settings(URI.create("http://localhost:1"))));
        var routes = GatewayRouteCatalog.routes();
        GatewayRouteValidator.validate(routes, upstreams);
        assertThatThrownBy(() -> GatewayRouteValidator.validate(List.of(), upstreams))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                () -> GatewayRouteValidator.validate(List.of(routes.getFirst(), routes.getFirst()), upstreams))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> GatewayRouteValidator.validate(routes, new UpstreamProperties(Map.of())))
                .isInstanceOf(IllegalStateException.class);
    }

    private void assertError(HttpClient client,
                             String method,
                             String path,
                             String bearer,
                             int status,
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
            assertThat(response.headers().firstValue("Allow")).contains("POST");
        }
        String id = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(UUID.fromString(id)).isNotNull();
        if (code != null) {
            var error = mapper.readTree(response.body()).get("error");
            assertThat(error.get("code").asString()).isEqualTo(code);
            assertThat(error.get("request_id").asString()).isEqualTo(id);
        }
    }
}
