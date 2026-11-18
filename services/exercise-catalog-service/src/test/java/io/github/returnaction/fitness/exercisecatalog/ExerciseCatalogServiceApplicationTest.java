package io.github.returnaction.fitness.exercisecatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.returnaction.fitness.exercisecatalog.catalog.service.ExerciseCatalogService;
import io.github.returnaction.fitness.exercisecatalog.shared.security.CatalogAuthenticationFilter;
import io.github.returnaction.fitness.exercisecatalog.shared.security.SecurityExceptionHandler;
import jakarta.servlet.ServletException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Этот тест проверяет HTTP-слой и не требует базы: отключаем DataSource,
        // JPA и Liquibase, чтобы контекст поднимался без PostgreSQL.
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"})
class ExerciseCatalogServiceApplicationTest {

    @LocalServerPort
    private int port;

    private static final String CATALOG = "/api/v1/exercise-catalog";

    @MockitoBean
    private ExerciseCatalogService catalog;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void exposesOnlyAllowedManagementEndpoints() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String path : List.of("health", "health/liveness", "health/readiness", "info", "prometheus")) {
                var response = client.send(request("/actuator/" + path).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(path).isEqualTo(200);
                assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
                if (path.equals("prometheus")) {
                    assertThat(response.body()).contains("jvm_memory");
                }
            }
            var response = client.send(request("/actuator/env").build(), HttpResponse.BodyHandlers.ofString());
            assertError(response, 401, "invalid_access_token");
        }
    }

    @Test
    void rejectsMissingAndMalformedBearerBeforeReadingCatalog() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String authorization : List.of("", "Bearer malformed")) {
                var request = request(CATALOG);
                if (!authorization.isEmpty()) {
                    request.header("Authorization", authorization);
                }
                var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                assertError(response, 401, "invalid_access_token");
                assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
            }
        }
        verifyNoInteractions(catalog);
    }

    @Test
    void rejectsQueryDeniedMethodAndUnsupportedResponseType() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            String bearer = bearer();
            String id = UUID.randomUUID().toString();
            var query = request(CATALOG + "?limit=1").header("Authorization", bearer).header("X-Request-Id", id);
            var invalid = client.send(query.build(), HttpResponse.BodyHandlers.ofString());
            assertError(invalid, 400, "invalid_request");
            assertThat(invalid.headers().firstValue("X-Request-Id")).contains(id);

            var denied = request(CATALOG).header("Authorization", bearer).POST(HttpRequest.BodyPublishers.noBody());
            assertError(client.send(denied.build(), HttpResponse.BodyHandlers.ofString()), 403, "access_denied");

            var xml = request(CATALOG).header("Authorization", bearer).header("Accept", "application/xml");
            var response = client.send(xml.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(406);
            assertThat(response.body()).isEmpty();
        }
        verifyNoInteractions(catalog);
    }

    @Test
    void reportsUnavailableCatalogWithRetryAfter() throws Exception {
        when(catalog.getExercises()).thenThrow(new CannotCreateTransactionException("test"));
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request(CATALOG).header("Authorization", bearer()).build(),
                HttpResponse.BodyHandlers.ofString());
            assertError(response, 503, "catalog_unavailable");
            assertThat(response.headers().firstValue("Retry-After")).contains("1");
        }
    }

    @Test
    void clearsUserContextWhenFilterChainFails() throws Exception {
        var filter = new CatalogAuthenticationFilter(mock(SecurityExceptionHandler.class));
        var request = new MockHttpServletRequest("GET", CATALOG);
        request.addHeader("Authorization", bearer());
        org.junit.jupiter.api.Assertions.assertThrows(ServletException.class, () ->
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated()).isTrue();
                throw new ServletException("test");
            }));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode());
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        String id = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        var error = mapper.readTree(response.body()).get("error");
        assertThat(error.get("code").asString()).isEqualTo(code);
        assertThat(error.get("message").asString()).isNotBlank();
        assertThat(error.get("details").isArray()).isTrue();
        assertThat(error.get("requestId").asString()).isEqualTo(id);
    }

    private String bearer() throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(new byte[32]));
        return "Bearer " + jwt.serialize();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    }
}
