package io.github.returnaction.fitness.challenge;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
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
import io.github.returnaction.fitness.challenge.service.ChallengeRecoveryWorker;
import io.github.returnaction.fitness.challenge.service.ChallengeSealService;
import io.github.returnaction.fitness.contracts.coins.stake.v1.BeginStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.BeginStakeSetResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FinalizeStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FinalizeStakeSetResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.PutStakeBatchRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.PutStakeBatchResponse;
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
class ChallengeSealFlowIntegrationTest {

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
    private ChallengeSealService sealService;

    @Autowired
    private ChallengeRecoveryWorker worker;

    @MockitoBean
    private CoinsStakeClient coins;

    @Test
    void sealsAfterSecondParticipantAndActivatesChallenge() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = publishedChallenge(owner);
        assertEquals("OPEN", challengeStatus(challengeId));
        assertEquals(0, stakeSetCount(challengeId));

        when(coins.reserveStake(any(ReserveStakeRequest.class))).thenReturn(reserved(UUID.randomUUID()));
        mockSuccessfulSeal();
        var joined = join(UUID.randomUUID(), challengeId, UUID.randomUUID());

        assertEquals(200, joined.statusCode());
        assertEquals("ACTIVE", challengeStatus(challengeId));
        assertEquals(1, stakeSetCount(challengeId));
        assertEquals("SEALED", stakeSetState(challengeId));
        assertNotNull(stakeSetDigest(challengeId));
        assertEquals(2, count("SELECT count(*) FROM challenge_participants WHERE challenge_id = ? " +
                "AND status = 'ACTIVE'", UUID.fromString(challengeId)));
        verify(coins).beginStakeSet(any(BeginStakeSetRequest.class));
        verify(coins).putStakeBatch(any(PutStakeBatchRequest.class));
        verify(coins).finalizeStakeSet(any(FinalizeStakeSetRequest.class));

        sealService.sealIfFull(UUID.fromString(challengeId));
        verify(coins, times(1)).beginStakeSet(any(BeginStakeSetRequest.class));
    }

    @Test
    void resumesCollectingSealAfterUnknownOutcome() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = publishedChallenge(owner);
        when(coins.beginStakeSet(any(BeginStakeSetRequest.class)))
                .thenThrow(new CoinsOutcomeUnknownException(new RuntimeException("timeout")));
        when(coins.reserveStake(any(ReserveStakeRequest.class))).thenReturn(reserved(UUID.randomUUID()));

        var joined = join(UUID.randomUUID(), challengeId, UUID.randomUUID());
        assertEquals(200, joined.statusCode());
        assertEquals("OPEN", challengeStatus(challengeId));
        assertEquals("COLLECTING", stakeSetState(challengeId));

        clearInvocations(coins);
        mockSuccessfulSeal();
        worker.recover();

        assertEquals("ACTIVE", challengeStatus(challengeId));
        assertEquals("SEALED", stakeSetState(challengeId));
        verify(coins).finalizeStakeSet(any(FinalizeStakeSetRequest.class));
    }

    private void mockSuccessfulSeal() {
        when(coins.beginStakeSet(any(BeginStakeSetRequest.class)))
                .thenReturn(BeginStakeSetResponse.newBuilder().build());
        when(coins.putStakeBatch(any(PutStakeBatchRequest.class)))
                .thenReturn(PutStakeBatchResponse.newBuilder().build());
        when(coins.finalizeStakeSet(any(FinalizeStakeSetRequest.class)))
                .thenReturn(FinalizeStakeSetResponse.newBuilder().build());
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

    private long stakeSetCount(String challengeId) {
        return jdbc.queryForObject("SELECT count(*) FROM challenge_stake_sets WHERE challenge_id = ?",
                Long.class, UUID.fromString(challengeId));
    }

    private String stakeSetState(String challengeId) {
        return jdbc.queryForObject("SELECT state FROM challenge_stake_sets WHERE challenge_id = ?",
                String.class, UUID.fromString(challengeId));
    }

    private String stakeSetDigest(String challengeId) {
        return jdbc.queryForObject("""
                SELECT encode(digest, 'hex') FROM challenge_stake_sets WHERE challenge_id = ?
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
