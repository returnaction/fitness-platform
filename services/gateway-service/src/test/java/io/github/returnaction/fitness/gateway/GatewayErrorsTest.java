package io.github.returnaction.fitness.gateway;

import io.github.returnaction.fitness.gateway.request.RequestIdFilter;
import io.github.returnaction.fitness.gateway.security.jwks.JwksInitializer;
import io.github.returnaction.fitness.gateway.security.session.SessionVerificationClient;
import io.github.returnaction.fitness.gateway.timeout.RequestDeadlineFilter;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayErrorsTest {

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

    @Test
    void contextFiltersAreRegisteredOnce() {
        var registrations = servletContext.getFilterRegistrations().values();
        assertThat(registrations.stream()
                .filter(registration -> registration.getClassName()
                        .equals(
                                RequestIdFilter.class.getName()))
                .count()).isEqualTo(1);
        assertThat(registrations.stream()
                .filter(registration -> registration.getClassName()
                        .equals(
                                RequestDeadlineFilter.class.getName()))
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
            assertThat(error.get("request_id").asString()).isEqualTo(id);
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
        assertThat(mapper.readTree(response.getContentAsByteArray()).get("error").get("request_id").asString())
                .isEqualTo(id);
        byte[] before = response.getContentAsByteArray();
        response.flushBuffer();
        assertThatThrownBy(() -> writer.write(response, 500, GatewayErrorCode.INTERNAL_ERROR, id))
                .isInstanceOf(IllegalStateException.class);
        assertThat(response.getContentAsByteArray()).isEqualTo(before);
    }
}
