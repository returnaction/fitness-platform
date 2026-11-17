package io.github.returnaction.fitness.challenge;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.returnaction.fitness.challenge.coins.CoinsStakeClient;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsOutcomeUnknownException;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsRejectedException;
import io.github.returnaction.fitness.challenge.service.ChallengeRecoveryWorker;
import io.github.returnaction.fitness.contracts.coins.stake.v1.CompleteStakeCancellationRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.CompleteStakeCancellationResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FreezeStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FreezeStakeSetResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.GetStakeOperationResponse;
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
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChallengeRecoveryWorkerIntegrationTest {

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

    @Autowired
    private ChallengeRecoveryWorker worker;

    @MockitoBean
    private CoinsStakeClient coins;

    @Test
    void recoversPendingPublish() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = createFilledDraft(owner);
        when(coins.reserveStake(any(ReserveStakeRequest.class)))
                .thenThrow(new CoinsOutcomeUnknownException(new RuntimeException("timeout")));
        var publish = send("POST", "/api/v1/challenges/" + challengeId + "/publish", owner,
                UUID.randomUUID(), "{\"version\":2}");
        assertEquals(202, publish.statusCode());
        assertEquals("PENDING", operationStatus(challengeId));

        UUID holdId = UUID.randomUUID();
        when(coins.getStakeOperation(any())).thenReturn(GetStakeOperationResponse.newBuilder()
                .setReserve(ReserveStakeResponse.newBuilder()
                        .setOutcome(ReserveOutcome.RESERVE_OUTCOME_RESERVED)
                        .setHoldId(holdId.toString())
                        .build())
                .build());

        worker.recover();

        assertEquals("OPEN", challengeStatus(challengeId));
        assertEquals(1, count("SELECT count(*) FROM challenge_participants WHERE challenge_id = ?",
                UUID.fromString(challengeId)));
        assertEquals("CONFIRMED", operationStatus(challengeId));
    }

    @Test
    void recoversPendingReleaseIntent() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = createFilledDraft(owner);
        when(coins.reserveStake(any(ReserveStakeRequest.class))).thenReturn(reserved(UUID.randomUUID()));
        var publish = send("POST", "/api/v1/challenges/" + challengeId + "/publish", owner,
                UUID.randomUUID(), "{\"version\":2}");
        assertEquals(200, publish.statusCode());

        when(coins.freezeStakeSet(any(FreezeStakeSetRequest.class))).thenReturn(
                FreezeStakeSetResponse.newBuilder().build());
        when(coins.releaseStake(any(ReleaseStakeRequest.class)))
                .thenThrow(new CoinsOutcomeUnknownException(new RuntimeException("timeout")));
        var cancel = send("POST", "/api/v1/challenges/" + challengeId + "/cancel", owner,
                UUID.randomUUID(), "{}");
        assertEquals(200, cancel.statusCode());
        assertEquals("PENDING", intentStatus(challengeId));

        when(coins.getStakeOperation(any())).thenThrow(
                new CoinsRejectedException("NOT_FOUND", "OPERATION_NOT_FOUND", new RuntimeException()));
        when(coins.releaseStake(any(ReleaseStakeRequest.class))).thenReturn(
                ReleaseStakeResponse.newBuilder()
                        .setOutcome(ReleaseOutcome.RELEASE_OUTCOME_RELEASED)
                        .build());
        when(coins.completeStakeCancellation(any(CompleteStakeCancellationRequest.class))).thenReturn(
                CompleteStakeCancellationResponse.newBuilder().build());

        worker.recover();

        assertEquals("CONFIRMED", intentStatus(challengeId));
        var read = send("GET", "/api/v1/challenges/" + challengeId, owner, null, null);
        assertEquals("COMPLETED", mapper.readTree(read.body()).get("refundStatus").asString());
    }

    private String createFilledDraft(UUID owner) throws Exception {
        var created = send("POST", "/api/v1/challenges/drafts", owner, UUID.randomUUID(), "{}");
        assertEquals(201, created.statusCode());
        String challengeId = mapper.readTree(created.body()).get("id").asString();
        var patched = send("PATCH", "/api/v1/challenges/" + challengeId + "/draft", owner,
                UUID.randomUUID(), """
                        {"version":1,"title":"Неделя отжиманий","description":"Тренируемся",
                         "stakeAmount":10,"durationDays":7}
                        """);
        assertEquals(200, patched.statusCode());
        return challengeId;
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

    private String challengeStatus(String challengeId) {
        return jdbc.queryForObject("SELECT status FROM challenges WHERE id = ?", String.class,
                UUID.fromString(challengeId));
    }

    private String operationStatus(String challengeId) {
        return jdbc.queryForObject("""
                SELECT status FROM challenge_operations WHERE challenge_id = ? AND kind = 'PUBLISH'
                """, String.class, UUID.fromString(challengeId));
    }

    private String intentStatus(String challengeId) {
        return jdbc.queryForObject("SELECT status FROM challenge_release_intents WHERE challenge_id = ?",
                String.class, UUID.fromString(challengeId));
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
