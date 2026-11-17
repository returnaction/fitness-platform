package io.github.returnaction.fitness.challenge;

import static io.github.returnaction.fitness.challenge.domain.ChallengeOperationKind.JOIN;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.returnaction.fitness.challenge.entity.ChallengeOperationEntity;
import io.github.returnaction.fitness.challenge.repository.ChallengeOperationRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChallengeDraftFlowIntegrationTest {

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

    @Autowired
    private ChallengeOperationRepository operations;

    @Test
    void createsDraftAndReplaysIdempotentResponse() throws Exception {
        UUID user = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        try (var client = HttpClient.newHttpClient()) {
            var missingKey = send(client, "POST", "/api/v1/challenges/drafts", user, null, "{}");
            assertEquals(400, missingKey.statusCode());
            assertEquals("invalid_request", errorCode(missingKey));

            var created = send(client, "POST", "/api/v1/challenges/drafts", user, key.toString(), "{}");
            assertEquals(201, created.statusCode());
            JsonNode body = mapper.readTree(created.body());
            String id = body.get("id").asString();
            assertEquals("DRAFT", body.get("status").asString());
            assertEquals(1, body.get("version").asInt());
            assertEquals("/api/v1/challenges/" + id, created.headers().firstValue("Location").orElseThrow());
            assertNotNull(created.headers().firstValue("X-Request-Id").orElse(null));

            var replay = send(client, "POST", "/api/v1/challenges/drafts", user, key.toString(), "{}");
            assertEquals(201, replay.statusCode());
            assertEquals(id, mapper.readTree(replay.body()).get("id").asString());
            assertEquals(1, mapper.readTree(replay.body()).get("version").asInt());
        }
    }

    @Test
    void updatesDraftAndEnforcesVersionOwnershipAndIdempotency() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID createKey = UUID.randomUUID();
        UUID updateKey = UUID.randomUUID();
        try (var client = HttpClient.newHttpClient()) {
            var created = send(client, "POST", "/api/v1/challenges/drafts", owner, createKey.toString(), "{}");
            assertEquals(201, created.statusCode());
            String id = mapper.readTree(created.body()).get("id").asString();

            String updateBody = """
                    {"version":1,"title":"Неделя отжиманий","description":"Тренируемся вместе",
                     "stakeAmount":10,"durationDays":7}
                    """;
            var updated = send(client, "PATCH", "/api/v1/challenges/" + id + "/draft", owner,
                    updateKey.toString(), updateBody);
            assertEquals(200, updated.statusCode());
            assertEquals(2, mapper.readTree(updated.body()).get("version").asInt());

            var stale = send(client, "PATCH", "/api/v1/challenges/" + id + "/draft", owner,
                    UUID.randomUUID().toString(), "{\"version\":1,\"title\":\"Другое\"}");
            assertEquals(409, stale.statusCode());
            assertEquals("version_conflict", errorCode(stale));

            var invalid = send(client, "PATCH", "/api/v1/challenges/" + id + "/draft", owner,
                    UUID.randomUUID().toString(), "{\"version\":2,\"title\":\"" + "x".repeat(121) + "\"}");
            assertEquals(422, invalid.statusCode());
            assertEquals("validation_error", errorCode(invalid));

            var replay = send(client, "PATCH", "/api/v1/challenges/" + id + "/draft", owner,
                    updateKey.toString(), updateBody);
            assertEquals(200, replay.statusCode());
            assertEquals(2, mapper.readTree(replay.body()).get("version").asInt());

            var conflict = send(client, "PATCH", "/api/v1/challenges/" + id + "/draft", owner,
                    updateKey.toString(), "{\"version\":2,\"title\":\"Другое\"}");
            assertEquals(409, conflict.statusCode());
            assertEquals("idempotency_key_conflict", errorCode(conflict));

            var own = send(client, "GET", "/api/v1/challenges/" + id, owner, null, null);
            assertEquals(200, own.statusCode());
            JsonNode details = mapper.readTree(own.body());
            assertEquals("Неделя отжиманий", details.get("title").asString());
            assertEquals(10, details.get("stakeAmount").asLong());
            assertEquals(7, details.get("durationDays").asInt());
            assertEquals("HEAD_TO_HEAD", details.get("mode").asString());
            assertEquals("PUBLIC", details.get("visibility").asString());
            assertTrue(details.get("viewer").get("isCreator").asBoolean());
            assertEquals("NONE", details.get("refundStatus").asString());
            assertEquals("UNKNOWN", details.get("settlementStatus").asString());
            assertEquals(0, details.get("participants").get("joined").asInt());

            var foreign = send(client, "GET", "/api/v1/challenges/" + id, stranger, null, null);
            assertEquals(404, foreign.statusCode());
            assertEquals("challenge_not_found", errorCode(foreign));

            var foreignPatch = send(client, "PATCH", "/api/v1/challenges/" + id + "/draft", stranger,
                    UUID.randomUUID().toString(), "{\"version\":2,\"title\":\"Чужое\"}");
            assertEquals(404, foreignPatch.statusCode());
            assertEquals("challenge_not_found", errorCode(foreignPatch));
        }
    }

    @Test
    void operationIsVisibleOnlyToItsOwner() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        try (var client = HttpClient.newHttpClient()) {
            var created = send(client, "POST", "/api/v1/challenges/drafts", owner,
                    UUID.randomUUID().toString(), "{}");
            UUID challengeId = UUID.fromString(mapper.readTree(created.body()).get("id").asString());
            var operation = operations.save(new ChallengeOperationEntity(challengeId, owner, JOIN, Instant.now()));

            var own = send(client, "GET", "/api/v1/challenge-operations/" + operation.getId(), owner, null, null);
            assertEquals(200, own.statusCode());
            JsonNode body = mapper.readTree(own.body());
            assertEquals("JOIN", body.get("kind").asString());
            assertEquals("PENDING", body.get("status").asString());
            assertEquals(challengeId.toString(), body.get("challengeId").asString());
            assertTrue(body.get("result").isNull());

            var foreign = send(client, "GET", "/api/v1/challenge-operations/" + operation.getId(), stranger,
                    null, null);
            assertEquals(404, foreign.statusCode());
            assertEquals("operation_not_found", errorCode(foreign));
        }
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, UUID user,
                                      String idempotencyKey, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token(user))
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, UTF_8));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(UTF_8));
    }

    private String errorCode(HttpResponse<String> response) {
        return mapper.readTree(response.body()).get("error").get("code").asString();
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
