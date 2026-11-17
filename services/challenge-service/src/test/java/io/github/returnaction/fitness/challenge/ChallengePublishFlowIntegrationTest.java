package io.github.returnaction.fitness.challenge;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.returnaction.fitness.challenge.coins.CoinsStakeClient;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsOutcomeUnknownException;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveOutcome;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveStakeRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveStakeResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
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
class ChallengePublishFlowIntegrationTest {

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
    private JdbcTemplate jdbc;

    @MockitoBean
    private CoinsStakeClient coins;

    @Test
    void publishesDraftAfterConfirmedReserveAndReplaysResponse() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = createFilledDraft(owner);
        UUID holdId = UUID.randomUUID();
        when(coins.reserveStake(any(ReserveStakeRequest.class))).thenReturn(reserved(holdId));
        UUID publishKey = UUID.randomUUID();

        var published = publish(owner, challengeId, publishKey, 2);

        assertEquals(200, published.statusCode());
        JsonNode body = mapper.readTree(published.body());
        assertEquals("OPEN", body.get("status").asString());
        assertEquals("JOINED", body.get("participationStatus").asString());
        assertEquals("OPEN", challengeStatus(challengeId));
        assertEquals(1, count("SELECT count(*) FROM challenge_participants WHERE challenge_id = ?",
                UUID.fromString(challengeId)));
        assertEquals("CONFIRMED", operationStatus(challengeId));
        assertEquals(holdId.toString(), operationHoldId(challengeId));

        var replay = publish(owner, challengeId, publishKey, 2);
        assertEquals(200, replay.statusCode());
        assertEquals(body, mapper.readTree(replay.body()));
        verify(coins, times(1)).reserveStake(any(ReserveStakeRequest.class));
    }

    @Test
    void keepsDraftWhenCoinsRejectsReserve() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = createFilledDraft(owner);
        when(coins.reserveStake(any(ReserveStakeRequest.class))).thenReturn(
                ReserveStakeResponse.newBuilder()
                        .setOutcome(ReserveOutcome.RESERVE_OUTCOME_INSUFFICIENT_FUNDS)
                        .build());

        var response = publish(owner, challengeId, UUID.randomUUID(), 2);

        assertEquals(409, response.statusCode());
        assertEquals("insufficient_coins", errorCode(response));
        assertEquals("DRAFT", challengeStatus(challengeId));
        assertEquals(0, count("SELECT count(*) FROM challenge_participants WHERE challenge_id = ?",
                UUID.fromString(challengeId)));
        assertEquals("FAILED", operationStatus(challengeId));
        assertEquals("insufficient_coins", operationFailureCode(challengeId));
    }

    @Test
    void reportsStakeConflictWithoutPublishing() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = createFilledDraft(owner);
        when(coins.reserveStake(any(ReserveStakeRequest.class))).thenReturn(
                ReserveStakeResponse.newBuilder()
                        .setOutcome(ReserveOutcome.RESERVE_OUTCOME_ACTIVE_HOLD_EXISTS)
                        .build());

        var response = publish(owner, challengeId, UUID.randomUUID(), 2);

        assertEquals(409, response.statusCode());
        assertEquals("stake_conflict", errorCode(response));
        assertEquals("DRAFT", challengeStatus(challengeId));
        assertEquals("FAILED", operationStatus(challengeId));
    }

    @Test
    void returns202AndKeepsOperationPendingWhenOutcomeIsUnknown() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = createFilledDraft(owner);
        when(coins.reserveStake(any(ReserveStakeRequest.class)))
                .thenThrow(new CoinsOutcomeUnknownException(new RuntimeException("timeout")));
        UUID publishKey = UUID.randomUUID();

        var response = publish(owner, challengeId, publishKey, 2);

        assertEquals(202, response.statusCode());
        JsonNode body = mapper.readTree(response.body());
        assertEquals("PENDING", body.get("status").asString());
        assertTrue(body.get("statusUrl").asString().startsWith("/api/v1/challenge-operations/"));
        assertEquals("DRAFT", challengeStatus(challengeId));
        assertEquals("PENDING", operationStatus(challengeId));

        var replay = publish(owner, challengeId, publishKey, 2);
        assertEquals(202, replay.statusCode());
        assertEquals(body.get("operationId").asString(),
                mapper.readTree(replay.body()).get("operationId").asString());
        verify(coins, times(1)).reserveStake(any(ReserveStakeRequest.class));
    }

    @Test
    void rejectsStaleVersionForeignCreatorAndMissingFields() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = createFilledDraft(owner);

        var stale = publish(owner, challengeId, UUID.randomUUID(), 1);
        assertEquals(409, stale.statusCode());
        assertEquals("version_conflict", errorCode(stale));

        var foreign = publish(UUID.randomUUID(), challengeId, UUID.randomUUID(), 2);
        assertEquals(404, foreign.statusCode());
        assertEquals("challenge_not_found", errorCode(foreign));

        UUID otherOwner = UUID.randomUUID();
        String emptyDraft = createDraft(otherOwner);
        var missingFields = publish(otherOwner, emptyDraft, UUID.randomUUID(), 1);
        assertEquals(422, missingFields.statusCode());
        assertEquals("validation_error", errorCode(missingFields));
    }

    private String createFilledDraft(UUID owner) throws Exception {
        String challengeId = createDraft(owner);
        var patched = send("PATCH", "/api/v1/challenges/" + challengeId + "/draft", owner,
                UUID.randomUUID(), """
                        {"version":1,"title":"Неделя отжиманий","description":"Тренируемся",
                         "stakeAmount":10,"durationDays":7}
                        """);
        assertEquals(200, patched.statusCode());
        return challengeId;
    }

    private String createDraft(UUID owner) throws Exception {
        var response = send("POST", "/api/v1/challenges/drafts", owner, UUID.randomUUID(), "{}");
        assertEquals(201, response.statusCode());
        return mapper.readTree(response.body()).get("id").asString();
    }

    private HttpResponse<String> publish(UUID user, String challengeId, UUID key, int version) throws Exception {
        return send("POST", "/api/v1/challenges/" + challengeId + "/publish", user, key,
                "{\"version\":" + version + "}");
    }

    private HttpResponse<String> send(String method, String path, UUID user, UUID key, String body)
            throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Authorization", "Bearer " + token(user))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, UTF_8));
            if (key != null) {
                builder.header("Idempotency-Key", key.toString());
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(UTF_8));
        }
    }

    private String errorCode(HttpResponse<String> response) {
        return mapper.readTree(response.body()).get("error").get("code").asString();
    }

    private String challengeStatus(String challengeId) {
        return jdbc.queryForObject("SELECT status FROM challenges WHERE id = ?", String.class,
                UUID.fromString(challengeId));
    }

    private String operationStatus(String challengeId) {
        return jdbc.queryForObject("""
                SELECT status FROM challenge_operations
                WHERE challenge_id = ? AND kind = 'PUBLISH'
                """, String.class, UUID.fromString(challengeId));
    }

    private String operationFailureCode(String challengeId) {
        return jdbc.queryForObject("""
                SELECT failure_code FROM challenge_operations
                WHERE challenge_id = ? AND kind = 'PUBLISH'
                """, String.class, UUID.fromString(challengeId));
    }

    private String operationHoldId(String challengeId) {
        return jdbc.queryForObject("""
                SELECT hold_id::text FROM challenge_operations
                WHERE challenge_id = ? AND kind = 'PUBLISH'
                """, String.class, UUID.fromString(challengeId));
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args),
                "count query must return a value");
    }

    private static ReserveStakeResponse reserved(UUID holdId) {
        return ReserveStakeResponse.newBuilder()
                .setOutcome(ReserveOutcome.RESERVE_OUTCOME_RESERVED)
                .setHoldId(holdId.toString())
                .build();
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
