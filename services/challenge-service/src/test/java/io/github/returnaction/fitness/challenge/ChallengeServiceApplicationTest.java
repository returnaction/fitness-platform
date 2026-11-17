package io.github.returnaction.fitness.challenge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChallengeServiceApplicationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Test
    void healthIsAvailableAndOtherRequestsAreDenied() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> health = client.send(request("/actuator/health"),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertNotNull(health.headers().firstValue("X-Request-Id").orElse(null));

            assertUnauthenticated(client.send(request("/unknown"), HttpResponse.BodyHandlers.ofString()));
            assertUnauthenticated(client.send(request("/actuator/env"), HttpResponse.BodyHandlers.ofString()));
            assertUnauthenticated(client.send(request("/api/v1/challenges/" + UUID.randomUUID()),
                    HttpResponse.BodyHandlers.ofString()));

            HttpResponse<String> metrics = client.send(request("/actuator/prometheus"),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("jvm_memory"));

            HttpResponse<String> authorized = client.send(
                    requestWithToken("/api/v1/challenges/" + UUID.randomUUID()),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, authorized.statusCode());
            assertTrue(authorized.body().contains("\"code\":\"not_found\""));
        }
    }

    private void assertUnauthenticated(HttpResponse<String> response) {
        assertEquals(401, response.statusCode());
        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertNotNull(requestId);
        assertTrue(response.body().contains("\"code\":\"unauthenticated\""));
        assertTrue(response.body().contains(requestId));
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    }

    private HttpRequest requestWithToken(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token())
                .GET()
                .build();
    }

    private static String token() {
        try {
            var claims = new JWTClaimsSet.Builder()
                    .subject(UUID.randomUUID().toString())
                    .build();
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(new byte[32]));
            return jwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
