package io.github.returnaction.fitness.challenge;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
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
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseStakeRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseStakeResponse;
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
class ChallengeJoinFlowIntegrationTest {

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
    void joinsSecondParticipantAndReplaysResponse() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = publishedChallenge(owner);
        UUID joinKey = UUID.randomUUID();
        UUID joiner = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        clearInvocations(coins);
        when(coins.reserveStake(any(ReserveStakeRequest.class))).thenReturn(reserved(holdId));

        var joined = join(joiner, challengeId, joinKey);

        assertEquals(200, joined.statusCode());
        JsonNode body = mapper.readTree(joined.body());
        assertEquals(challengeId, body.get("challengeId").asString());
        assertEquals("JOINED", body.get("participationStatus").asString());
        assertEquals(2, count("SELECT count(*) FROM challenge_participants WHERE challenge_id = ?",
                UUID.fromString(challengeId)));
        assertEquals("CONFIRMED", operationStatus(challengeId, "JOIN"));
        assertEquals(holdId.toString(), operationHoldId(challengeId, "JOIN"));

        var replay = join(joiner, challengeId, joinKey);
        assertEquals(200, replay.statusCode());
        verify(coins, times(1)).reserveStake(any(ReserveStakeRequest.class));
    }

    @Test
    void rejectsAlreadyJoinedAndNonOpenStates() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = publishedChallenge(owner);
        clearInvocations(coins);

        var already = join(owner, challengeId, UUID.randomUUID());
        assertEquals(409, already.statusCode());
        assertEquals("already_joined", errorCode(already));
        verify(coins, never()).reserveStake(any(ReserveStakeRequest.class));

        jdbc.update("UPDATE challenges SET status = 'ACTIVE', started_at = CURRENT_TIMESTAMP WHERE id = ?",
                UUID.fromString(challengeId));
        var started = join(UUID.randomUUID(), challengeId, UUID.randomUUID());
        assertEquals(409, started.statusCode());
        assertEquals("challenge_started", errorCode(started));

        UUID otherOwner = UUID.randomUUID();
        String cancelledId = publishedChallenge(otherOwner);
        jdbc.update("UPDATE challenges SET status = 'CANCELLED' WHERE id = ?", UUID.fromString(cancelledId));
        var cancelled = join(UUID.randomUUID(), cancelledId, UUID.randomUUID());
        assertEquals(409, cancelled.statusCode());
        assertEquals("challenge_cancelled", errorCode(cancelled));
    }

    @Test
    void mapsCoinsBusinessOutcomes() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = publishedChallenge(owner);
        clearInvocations(coins);
        when(coins.reserveStake(any(ReserveStakeRequest.class))).thenReturn(
                ReserveStakeResponse.newBuilder()
                        .setOutcome(ReserveOutcome.RESERVE_OUTCOME_INSUFFICIENT_FUNDS)
                        .build());

        var insufficient = join(UUID.randomUUID(), challengeId, UUID.randomUUID());
        assertEquals(409, insufficient.statusCode());
        assertEquals("insufficient_coins", errorCode(insufficient));
        assertEquals(1, count("SELECT count(*) FROM challenge_participants WHERE challenge_id = ?",
                UUID.fromString(challengeId)));

        when(coins.reserveStake(any(ReserveStakeRequest.class))).thenReturn(
                ReserveStakeResponse.newBuilder()
                        .setOutcome(ReserveOutcome.RESERVE_OUTCOME_ACTIVE_HOLD_EXISTS)
                        .build());
        var conflict = join(UUID.randomUUID(), challengeId, UUID.randomUUID());
        assertEquals(409, conflict.statusCode());
        assertEquals("stake_conflict", errorCode(conflict));
    }

    @Test
    void returns202ForUnknownOutcomeAndReplaysIt() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = publishedChallenge(owner);
        clearInvocations(coins);
        when(coins.reserveStake(any(ReserveStakeRequest.class)))
                .thenThrow(new CoinsOutcomeUnknownException(new RuntimeException("timeout")));
        UUID joinKey = UUID.randomUUID();
        UUID joiner = UUID.randomUUID();

        var response = join(joiner, challengeId, joinKey);

        assertEquals(202, response.statusCode());
        JsonNode body = mapper.readTree(response.body());
        assertEquals("PENDING", body.get("status").asString());
        assertTrue(body.get("statusUrl").asString().startsWith("/api/v1/challenge-operations/"));
        assertEquals("PENDING", operationStatus(challengeId, "JOIN"));

        var replay = join(joiner, challengeId, joinKey);
        assertEquals(202, replay.statusCode());
        assertEquals(body.get("operationId").asString(),
                mapper.readTree(replay.body()).get("operationId").asString());
        verify(coins, times(1)).reserveStake(any(ReserveStakeRequest.class));
    }

    @Test
    void releasesHoldWhenPlaceIsAlreadyTaken() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID filledBy = UUID.randomUUID();
        String challengeId = publishedChallenge(owner);
        UUID challengeUuid = UUID.fromString(challengeId);
        clearInvocations(coins);
        when(coins.reserveStake(any(ReserveStakeRequest.class))).thenAnswer(invocation -> {
            jdbc.update("""
                    INSERT INTO challenge_participants
                        (id, challenge_id, user_id, status, join_sequence)
                    VALUES (?, ?, ?, 'JOINED', 2)
                    """, UUID.randomUUID(), challengeUuid, filledBy);
            return reserved(UUID.randomUUID());
        });
        when(coins.releaseStake(any(ReleaseStakeRequest.class))).thenReturn(
                ReleaseStakeResponse.newBuilder()
                        .setReleaseOperationId(UUID.randomUUID().toString())
                        .setHoldId(UUID.randomUUID().toString())
                        .setOutcome(io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseOutcome
                                .RELEASE_OUTCOME_RELEASED)
                        .build());

        var response = join(UUID.randomUUID(), challengeId, UUID.randomUUID());

        assertEquals(409, response.statusCode());
        assertEquals("challenge_full", errorCode(response));
        verify(coins).releaseStake(any(ReleaseStakeRequest.class));
        assertEquals(2, count("SELECT count(*) FROM challenge_participants WHERE challenge_id = ?", challengeUuid));
        assertEquals("FAILED", operationStatus(challengeId, "JOIN"));
        assertEquals("challenge_full", operationFailureCode(challengeId, "JOIN"));
    }

    private String publishedChallenge(UUID owner) throws Exception {
        var created = send("POST", "/api/v1/challenges/drafts", owner, UUID.randomUUID(), "{}");
        assertEquals(201, created.statusCode());
        String challengeId = mapper.readTree(created.body()).get("id").asString();
        var patched = send("PATCH", "/api/v1/challenges/" + challengeId + "/draft", owner,
                UUID.randomUUID(), """
                        {"version":1,"title":"Неделя отжиманий","description":"Тренируемся",
                         "stakeAmount":10,"durationDays":7}
                        """);
        assertEquals(200, patched.statusCode());
        when(coins.reserveStake(any(ReserveStakeRequest.class))).thenReturn(reserved(UUID.randomUUID()));
        var published = send("POST", "/api/v1/challenges/" + challengeId + "/publish", owner,
                UUID.randomUUID(), "{\"version\":2}");
        assertEquals(200, published.statusCode());
        return challengeId;
    }

    private HttpResponse<String> join(UUID user, String challengeId, UUID key) throws Exception {
        return send("POST", "/api/v1/challenges/" + challengeId + "/joins", user, key, "{}");
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

    private String operationStatus(String challengeId, String kind) {
        return jdbc.queryForObject("""
                SELECT status FROM challenge_operations
                WHERE challenge_id = ? AND kind = ?
                """, String.class, UUID.fromString(challengeId), kind);
    }

    private String operationFailureCode(String challengeId, String kind) {
        return jdbc.queryForObject("""
                SELECT failure_code FROM challenge_operations
                WHERE challenge_id = ? AND kind = ?
                """, String.class, UUID.fromString(challengeId), kind);
    }

    private String operationHoldId(String challengeId, String kind) {
        return jdbc.queryForObject("""
                SELECT hold_id::text FROM challenge_operations
                WHERE challenge_id = ? AND kind = ?
                """, String.class, UUID.fromString(challengeId), kind);
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
