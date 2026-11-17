package io.github.returnaction.fitness.challenge;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
import io.github.returnaction.fitness.contracts.coins.stake.v1.CompleteStakeCancellationRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.CompleteStakeCancellationResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FreezeStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FreezeStakeSetResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseOutcome;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseStakeRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseStakeResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveOutcome;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveStakeRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveStakeResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
class ChallengeCancelFlowIntegrationTest {

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
    void cancelsAndCompletesRefundIdempotently() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = publishedChallenge(owner);
        UUID cancelKey = UUID.randomUUID();
        mockSuccessfulCancellation();

        var cancelled = cancel(owner, challengeId, cancelKey);

        assertEquals(200, cancelled.statusCode());
        JsonNode body = mapper.readTree(cancelled.body());
        assertEquals("CANCELLED", body.get("status").asString());
        assertEquals("COMPLETED", body.get("refundStatus").asString());
        assertEquals("CANCELLED", challengeStatus(challengeId));
        assertEquals("CONFIRMED", intentStatus(challengeId));
        verify(coins).freezeStakeSet(any(FreezeStakeSetRequest.class));
        verify(coins).releaseStake(any(ReleaseStakeRequest.class));
        verify(coins).completeStakeCancellation(any(CompleteStakeCancellationRequest.class));

        var replay = cancel(owner, challengeId, cancelKey);
        assertEquals(200, replay.statusCode());
        assertEquals(body, mapper.readTree(replay.body()));
        verify(coins, times(1)).freezeStakeSet(any(FreezeStakeSetRequest.class));

        var again = cancel(owner, challengeId, UUID.randomUUID());
        assertEquals(200, again.statusCode());
        assertEquals("COMPLETED", mapper.readTree(again.body()).get("refundStatus").asString());
        verify(coins, times(1)).freezeStakeSet(any(FreezeStakeSetRequest.class));
    }

    @Test
    void rejectsCancellationWhenAnotherParticipantExists() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = publishedChallenge(owner);
        when(coins.reserveStake(any(ReserveStakeRequest.class))).thenReturn(reserved(UUID.randomUUID()));
        var joined = join(UUID.randomUUID(), challengeId, UUID.randomUUID());
        assertEquals(200, joined.statusCode());

        var response = cancel(owner, challengeId, UUID.randomUUID());

        assertEquals(409, response.statusCode());
        assertEquals("cancellation_not_allowed", errorCode(response));
        assertEquals("ACTIVE", challengeStatus(challengeId));
        verify(coins, never()).freezeStakeSet(any(FreezeStakeSetRequest.class));
    }

    @Test
    void rejectsForeignCreator() throws Exception {
        String challengeId = publishedChallenge(UUID.randomUUID());

        var response = cancel(UUID.randomUUID(), challengeId, UUID.randomUUID());

        assertEquals(404, response.statusCode());
        assertEquals("challenge_not_found", errorCode(response));
    }

    @Test
    void leavesRefundPendingWhenCoinsOutcomeIsUnknown() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = publishedChallenge(owner);
        when(coins.freezeStakeSet(any(FreezeStakeSetRequest.class))).thenReturn(
                FreezeStakeSetResponse.newBuilder()
                        .setCancellationId(UUID.randomUUID().toString())
                        .setChallengeId(challengeId)
                        .build());
        when(coins.releaseStake(any(ReleaseStakeRequest.class)))
                .thenThrow(new CoinsOutcomeUnknownException(new RuntimeException("timeout")));

        var response = cancel(owner, challengeId, UUID.randomUUID());

        assertEquals(200, response.statusCode());
        assertEquals("PENDING", mapper.readTree(response.body()).get("refundStatus").asString());
        assertEquals("CANCELLED", challengeStatus(challengeId));
        assertEquals("PENDING", intentStatus(challengeId));

        var read = send("GET", "/api/v1/challenges/" + challengeId, owner, null, null);
        assertEquals("PENDING", mapper.readTree(read.body()).get("refundStatus").asString());
    }

    private void mockSuccessfulCancellation() {
        when(coins.freezeStakeSet(any(FreezeStakeSetRequest.class))).thenReturn(
                FreezeStakeSetResponse.newBuilder()
                        .setCancellationId(UUID.randomUUID().toString())
                        .build());
        when(coins.releaseStake(any(ReleaseStakeRequest.class))).thenReturn(
                ReleaseStakeResponse.newBuilder()
                        .setReleaseOperationId(UUID.randomUUID().toString())
                        .setHoldId(UUID.randomUUID().toString())
                        .setOutcome(ReleaseOutcome.RELEASE_OUTCOME_RELEASED)
                        .build());
        when(coins.completeStakeCancellation(any(CompleteStakeCancellationRequest.class))).thenReturn(
                CompleteStakeCancellationResponse.newBuilder()
                        .setCancellationId(UUID.randomUUID().toString())
                        .build());
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

    private HttpResponse<String> cancel(UUID user, String challengeId, UUID key) throws Exception {
        return send("POST", "/api/v1/challenges/" + challengeId + "/cancel", user, key, "{}");
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
                    .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body, UTF_8));
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

    private String intentStatus(String challengeId) {
        return jdbc.queryForObject("""
                SELECT status FROM challenge_release_intents WHERE challenge_id = ?
                """, String.class, UUID.fromString(challengeId));
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
