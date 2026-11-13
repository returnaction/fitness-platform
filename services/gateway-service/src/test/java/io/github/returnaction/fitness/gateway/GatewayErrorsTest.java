package io.github.returnaction.fitness.gateway;

import io.github.returnaction.fitness.gateway.request.RequestIdFilter;
import io.github.returnaction.fitness.gateway.security.jwks.JwksInitializer;
import io.github.returnaction.fitness.gateway.security.session.SessionVerificationClient;
import io.github.returnaction.fitness.gateway.timeout.RequestDeadlineFilter;
import io.github.returnaction.fitness.gateway.security.session.SessionVerificationFilter;
import io.github.returnaction.fitness.gateway.routing.RouteValidationFilter;
import io.github.returnaction.fitness.gateway.readiness.ReadinessGateFilter;
import io.github.returnaction.fitness.gateway.timeout.HttpDeadlineFilter;
import io.github.returnaction.fitness.gateway.forwarding.ProxyRequestBodyFilter;
import org.springframework.security.web.FilterChainProxy;
import jakarta.servlet.ServletContext;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import io.github.returnaction.fitness.gateway.error.GatewayErrorCode;
import io.github.returnaction.fitness.gateway.error.GatewayErrorWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(GatewayErrorsTest.FailingRoutes.class)
@ExtendWith(OutputCaptureExtension.class)
class GatewayErrorsTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingRoutes {

        @Bean
        @Order(-1)
        RouterFunction<ServerResponse> failingRoute() {
            return RouterFunctions.route().POST("/api/v1/auth/login", request -> {
                String mode = request.headers().firstHeader("X-Test-Failure");
                if ("405".equals(mode)) {
                    throw new HttpRequestMethodNotSupportedException("POST", List.of("GET"));
                }
                if ("415".equals(mode)) {
                    throw new HttpMediaTypeNotSupportedException("private-token");
                }
                throw new IllegalStateException("private-token");
            }).build();
        }
    }

    @Test
    void adviceHandlesFunctionalRoutesWithoutExposingDiagnostics(CapturedOutput output) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (int status : List.of(405, 415, 500)) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                        .header("X-Test-Failure", Integer.toString(status))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(status);
                assertThat(response.body()).contains("requestId").doesNotContain("private-token");
                if (status == 405) {
                    assertThat(response.headers().firstValue("Allow")).contains("GET");
                }
            }
        }
        assertThat(output.getAll()).doesNotContain("private-token");
    }

    @MockitoBean
    private SessionVerificationClient sessions;

    @MockitoBean
    private JwksInitializer jwks;

    @BeforeEach
    void loadedKeys() {
        Mockito.when(jwks.ready()).thenReturn(true);
    }

    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private GatewayErrorWriter writer;

    @Autowired
    private ServletContext servletContext;
    @Autowired
    private FilterChainProxy securityFilters;

    @Test
    void contextFiltersAreRegisteredOnce() {
        var registrations = servletContext.getFilterRegistrations().values();
        for (var type : List.of(ReadinessGateFilter.class, HttpDeadlineFilter.class, ProxyRequestBodyFilter.class)) {
            assertThat(registrations.stream().filter(item -> item.getClassName().equals(type.getName())).count())
                    .as(type.getSimpleName())
                    .isEqualTo(1);
        }
        for (var type : List.of(SessionVerificationFilter.class, RouteValidationFilter.class)) {
            assertThat(registrations.stream().filter(item -> item.getClassName().equals(type.getName())).count())
                    .isZero();
            assertThat(securityFilters.getFilterChains()
                    .stream()
                    .flatMap(chain -> chain.getFilters().stream())
                    .filter(type::isInstance)
                    .count()).as(type.getSimpleName()).isEqualTo(1);
        }
        assertThat(registrations.stream()
                .filter(registration -> registration.getClassName().equals(RequestIdFilter.class.getName()))
                .count()).isEqualTo(1);
        assertThat(registrations.stream()
                .filter(registration -> registration.getClassName().equals(RequestDeadlineFilter.class.getName()))
                .count()).isEqualTo(1);
    }

    @Test
    void securityErrorBeforeMvcUsesGeneratedIdAndCommonJson() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/logout-all"))
                    .header("X-Request-Id", "client-controlled")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
            String id = response.headers().firstValue("X-Request-Id").orElseThrow();
            assertThat(UUID.fromString(id)).isNotNull();
            var error = mapper.readTree(response.body()).get("error");
            assertThat(error.get("requestId").asString()).isEqualTo(id);
            assertThat(error.get("code").asString()).isEqualTo("invalid_access_token");
            assertThat(error.get("details").isArray()).isTrue();
            assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        }
    }

    @Test
    void writerReplacesBufferedBodyButNeverAppendsAfterCommit() throws Exception {
        var response = new MockHttpServletResponse();
        response.setHeader("Content-Encoding", "gzip");
        response.getOutputStream().write("old".getBytes(StandardCharsets.UTF_8));
        String id = UUID.randomUUID().toString();
        writer.write(response, 400, GatewayErrorCode.INVALID_REQUEST, id);
        assertThat(response.getHeader("Content-Encoding")).isNull();
        assertThat(mapper.readTree(response.getContentAsByteArray()).get("error").get("requestId").asString())
                .isEqualTo(id);
        byte[] before = response.getContentAsByteArray();
        response.flushBuffer();
        assertThatThrownBy(() -> writer.write(response, 500, GatewayErrorCode.INTERNAL_ERROR, id))
                .isInstanceOf(IllegalStateException.class);
        assertThat(response.getContentAsByteArray()).isEqualTo(before);
    }
}
