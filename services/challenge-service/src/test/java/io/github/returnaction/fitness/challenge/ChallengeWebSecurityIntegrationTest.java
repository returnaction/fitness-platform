package io.github.returnaction.fitness.challenge;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.returnaction.fitness.challenge.service.ChallengeDraftService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChallengeWebSecurityIntegrationTest {

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

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private ChallengeDraftService draftService;

    @Test
    void returns401WithCommonErrorFormatWithoutToken() throws Exception {
        var response = send("GET", "/api/v1/challenges/" + UUID.randomUUID(), null, null);

        assertEquals(401, response.statusCode());
        assertError(response, "unauthenticated");
    }

    @Test
    void returns400ForMalformedPathUuid() throws Exception {
        var response = send("GET", "/api/v1/challenges/not-a-uuid", UUID.randomUUID(), null);

        assertEquals(400, response.statusCode());
        assertError(response, "invalid_request");
    }

    @Test
    void returns503WithoutInternalDetailsWhenStorageIsUnavailable() throws Exception {
        when(draftService.getChallenge(any(), any()))
                .thenThrow(new DataAccessResourceFailureException("private database details"));

        var response = send("GET", "/api/v1/challenges/" + UUID.randomUUID(), UUID.randomUUID(), null);

        assertEquals(503, response.statusCode());
        assertError(response, "service_unavailable");
        assertTrue(response.body().contains("\"requestId\""));
        assertFalse(response.body().contains("private database details"));
    }

    private void assertError(HttpResponse<String> response, String code) throws Exception {
        String headerRequestId = response.headers().firstValue("X-Request-Id").orElse(null);
        assertNotNull(headerRequestId);
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
        JsonNode error = mapper.readTree(response.body()).get("error");
        assertEquals(code, error.get("code").asString());
        assertEquals(headerRequestId, error.get("requestId").asString());
        assertNotNull(error.get("message").asString());
    }

    private HttpResponse<String> send(String method, String path, UUID user, UUID idempotencyKey)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString("{}", UTF_8));
        if (user != null) {
            builder.header("Authorization", "Bearer " + token(user));
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey.toString());
        }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(UTF_8));
        }
    }

    private static String token(UUID userId) {
        try {
            var claims = new JWTClaimsSet.Builder().subject(userId.toString()).build();
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(new byte[32]));
            return jwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
