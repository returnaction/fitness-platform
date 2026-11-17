package io.github.returnaction.fitness.challenge;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.returnaction.fitness.challenge.coins.CoinsStakeClient;
import io.github.returnaction.fitness.contracts.coins.stake.v1.BeginStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.BeginStakeSetResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.CompleteStakeCancellationRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.CompleteStakeCancellationResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FinalizeStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FinalizeStakeSetResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FreezeStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FreezeStakeSetResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.PutStakeBatchRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.PutStakeBatchResponse;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class ChallengeConcurrencyIntegrationTest {

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
    void concurrentJoinsForLastSeatConfirmExactlyOneParticipant() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = publishedChallenge(owner);
        clearInvocations(coins);
        when(coins.reserveStake(any(ReserveStakeRequest.class)))
                .thenAnswer(invocation -> reserved(UUID.randomUUID()));
        when(coins.releaseStake(any(ReleaseStakeRequest.class))).thenReturn(released());

        HttpResponse<String> first;
        HttpResponse<String> second;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Future<HttpResponse<String>> firstFuture = executor.submit(() -> joinWithBarrier(barrier,
                    UUID.randomUUID(), challengeId));
            Future<HttpResponse<String>> secondFuture = executor.submit(() -> joinWithBarrier(barrier,
                    UUID.randomUUID(), challengeId));
            first = await(firstFuture);
            second = await(secondFuture);
        }

        List<Integer> statuses = List.of(first.statusCode(), second.statusCode()).stream().sorted().toList();
        assertEquals(List.of(200, 409), statuses);
        HttpResponse<String> rejected = first.statusCode() == 409 ? first : second;
        assertEquals("challenge_full", errorCode(rejected));
        assertEquals(2, count("SELECT count(*) FROM challenge_participants WHERE challenge_id = ?",
                UUID.fromString(challengeId)));
    }

    @Test
    void concurrentJoinAndCancelLeaveSingleFinalState() throws Exception {
        UUID owner = UUID.randomUUID();
        String challengeId = publishedChallenge(owner);
        clearInvocations(coins);
        when(coins.reserveStake(any(ReserveStakeRequest.class)))
                .thenAnswer(invocation -> reserved(UUID.randomUUID()));
        when(coins.releaseStake(any(ReleaseStakeRequest.class))).thenReturn(released());
        when(coins.freezeStakeSet(any(FreezeStakeSetRequest.class)))
                .thenReturn(FreezeStakeSetResponse.newBuilder().build());
        when(coins.completeStakeCancellation(any(CompleteStakeCancellationRequest.class)))
                .thenReturn(CompleteStakeCancellationResponse.newBuilder().build());
        when(coins.beginStakeSet(any(BeginStakeSetRequest.class)))
                .thenReturn(BeginStakeSetResponse.newBuilder().build());
        when(coins.putStakeBatch(any(PutStakeBatchRequest.class)))
                .thenReturn(PutStakeBatchResponse.newBuilder().build());
        when(coins.finalizeStakeSet(any(FinalizeStakeSetRequest.class)))
                .thenReturn(FinalizeStakeSetResponse.newBuilder().build());

        HttpResponse<String> join;
        HttpResponse<String> cancel;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Future<HttpResponse<String>> joinFuture = executor.submit(() -> joinWithBarrier(barrier,
                    UUID.randomUUID(), challengeId));
            Future<HttpResponse<String>> cancelFuture = executor.submit(() -> cancelWithBarrier(barrier,
                    owner, challengeId));
            join = await(joinFuture);
            cancel = await(cancelFuture);
        }

        if (cancel.statusCode() == 200) {
            assertEquals(409, join.statusCode());
            assertEquals("challenge_cancelled", errorCode(join));
            assertEquals("CANCELLED", challengeStatus(challengeId));
            assertEquals(1, participantCount(challengeId));
        } else {
            assertEquals(200, join.statusCode());
            assertEquals(409, cancel.statusCode());
            assertEquals("cancellation_not_allowed", errorCode(cancel));
            assertEquals(2, participantCount(challengeId));
            assertEquals("ACTIVE", challengeStatus(challengeId));
        }
    }

    private HttpResponse<String> joinWithBarrier(CyclicBarrier barrier, UUID user, String challengeId)
            throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return join(user, challengeId);
    }

    private HttpResponse<String> cancelWithBarrier(CyclicBarrier barrier, UUID user, String challengeId)
            throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return cancel(user, challengeId);
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

    private HttpResponse<String> join(UUID user, String challengeId) throws Exception {
        return send("POST", "/api/v1/challenges/" + challengeId + "/joins", user, UUID.randomUUID(), "{}");
    }

    private HttpResponse<String> cancel(UUID user, String challengeId) throws Exception {
        return send("POST", "/api/v1/challenges/" + challengeId + "/cancel", user, UUID.randomUUID(), "{}");
    }

    private HttpResponse<String> send(String method, String path, UUID user, UUID key, String body)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token(user))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body, UTF_8));
        if (key != null) {
            builder.header("Idempotency-Key", key.toString());
        }
        try (var client = HttpClient.newHttpClient()) {
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

    private long participantCount(String challengeId) {
        return count("SELECT count(*) FROM challenge_participants WHERE challenge_id = ?",
                UUID.fromString(challengeId));
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args),
                "count query must return a value");
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Concurrent request did not finish", exception);
        }
    }

    private static ReserveStakeResponse reserved(UUID holdId) {
        return ReserveStakeResponse.newBuilder()
                .setOutcome(ReserveOutcome.RESERVE_OUTCOME_RESERVED)
                .setHoldId(holdId.toString())
                .build();
    }

    private static ReleaseStakeResponse released() {
        return ReleaseStakeResponse.newBuilder()
                .setReleaseOperationId(UUID.randomUUID().toString())
                .setHoldId(UUID.randomUUID().toString())
                .setOutcome(ReleaseOutcome.RELEASE_OUTCOME_RELEASED)
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
