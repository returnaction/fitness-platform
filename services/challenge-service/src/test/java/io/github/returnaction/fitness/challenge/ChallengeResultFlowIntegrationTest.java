package io.github.returnaction.fitness.challenge;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.google.rpc.ErrorInfo;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.returnaction.fitness.challenge.coins.CoinsStakeClient;
import io.github.returnaction.fitness.challenge.result.grpc.ChallengeResultGrpcService;
import io.github.returnaction.fitness.contracts.challenge.results.v1.FinalizeResultRequest;
import io.github.returnaction.fitness.contracts.challenge.results.v1.FinalizeResultResponse;
import io.github.returnaction.fitness.contracts.challenge.results.v1.ListResultWinnersRequest;
import io.github.returnaction.fitness.contracts.challenge.results.v1.ListResultWinnersResponse;
import io.github.returnaction.fitness.contracts.challenge.results.v1.ResultOutcome;
import io.github.returnaction.fitness.contracts.coins.stake.v1.BeginStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.BeginStakeSetResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FinalizeStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FinalizeStakeSetResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.PutStakeBatchRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.PutStakeBatchResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveOutcome;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveStakeRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveStakeResponse;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
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
class ChallengeResultFlowIntegrationTest {

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
    private ChallengeResultGrpcService results;

    @MockitoBean
    private CoinsStakeClient coins;

    @Test
    void finalizesSingleWinnerAndReplaysTheSameResult() throws Exception {
        Fixture fixture = activeChallenge();
        UUID resultId = UUID.randomUUID();

        FinalizeResultResponse response = finalize(fixture, resultId, "SINGLE_WINNER",
                List.of(fixture.owner()));

        assertEquals(resultId.toString(), response.getResultId());
        assertEquals(1, response.getWinnerCount());
        assertFalse(response.getWinnersDigest().isBlank());
        assertEquals("FINISHED", challengeStatus(fixture.challengeId()));
        assertEquals("WINNER", participantStatus(fixture.challengeId(), fixture.owner()));
        assertEquals("COMPLETED", participantStatus(fixture.challengeId(), fixture.joiner()));
        assertEquals(1, count("SELECT count(*) FROM challenge_results WHERE id = ?", resultId));
        assertEquals(1, count("""
                SELECT count(*) FROM challenge_outbox
                WHERE event_type = 'challenge.finished' AND aggregate_id = ?
                """, UUID.fromString(fixture.challengeId())));

        assertEquals(response, finalize(fixture, resultId, "SINGLE_WINNER", List.of(fixture.owner())));
        assertEquals(1, count("SELECT count(*) FROM challenge_results WHERE challenge_id = ?",
                UUID.fromString(fixture.challengeId())));

        ListResultWinnersResponse winners = listWinners(resultId, "");
        assertEquals(List.of(fixture.owner().toString()), winners.getUserIdsList());
        assertTrue(winners.getNextCursor().isEmpty());
    }

    @Test
    void finalizesDrawAndMarksBothParticipantsCompleted() throws Exception {
        Fixture fixture = activeChallenge();

        FinalizeResultResponse response = finalize(fixture, UUID.randomUUID(), "DRAW",
                List.of(fixture.owner(), fixture.joiner()));

        assertEquals(2, response.getWinnerCount());
        assertEquals("COMPLETED", participantStatus(fixture.challengeId(), fixture.owner()));
        assertEquals("COMPLETED", participantStatus(fixture.challengeId(), fixture.joiner()));
    }

    @Test
    void rejectsUnsupportedOutcomeAndConflictingResult() throws Exception {
        Fixture fixture = activeChallenge();
        UUID resultId = UUID.randomUUID();

        var unsupported = new CapturingObserver<FinalizeResultResponse>();
        results.finalizeResult(request(fixture, resultId, "MULTIPLE_WINNERS",
                List.of(fixture.owner())), unsupported);
        assertEquals(Status.Code.FAILED_PRECONDITION, Status.fromThrowable(unsupported.error).getCode());
        assertEquals("UNSUPPORTED_OUTCOME", reason(unsupported.error));

        finalize(fixture, resultId, "SINGLE_WINNER", List.of(fixture.owner()));
        var conflict = new CapturingObserver<FinalizeResultResponse>();
        results.finalizeResult(request(fixture, resultId, "DRAW",
                List.of(fixture.owner(), fixture.joiner())), conflict);
        assertEquals(Status.Code.ALREADY_EXISTS, Status.fromThrowable(conflict.error).getCode());
        assertEquals("RESULT_ID_CONFLICT", reason(conflict.error));
    }

    @Test
    void rejectsWrongStakeSetAndUnknownWinner() throws Exception {
        Fixture fixture = activeChallenge();

        var request = request(fixture, UUID.randomUUID(), "SINGLE_WINNER", List.of(fixture.owner()))
                .toBuilder().setSetVersion(99).build();
        var wrongSet = new CapturingObserver<FinalizeResultResponse>();
        results.finalizeResult(request, wrongSet);
        assertEquals("STAKE_SET_MISMATCH", reason(wrongSet.error));

        var unknownWinner = new CapturingObserver<FinalizeResultResponse>();
        results.finalizeResult(request(fixture, UUID.randomUUID(), "SINGLE_WINNER",
                List.of(UUID.randomUUID())), unknownWinner);
        assertEquals("WINNER_NOT_PARTICIPANT", reason(unknownWinner.error));
    }

    private FinalizeResultResponse finalize(Fixture fixture, UUID resultId, String outcome, List<UUID> winners) {
        var observer = new CapturingObserver<FinalizeResultResponse>();
        results.finalizeResult(request(fixture, resultId, outcome, winners), observer);
        assertTrue(observer.error == null, () -> "Unexpected error: " + observer.error);
        return observer.value;
    }

    private FinalizeResultRequest request(Fixture fixture, UUID resultId, String outcome, List<UUID> winners) {
        return FinalizeResultRequest.newBuilder()
                .setResultId(resultId.toString())
                .setChallengeId(fixture.challengeId())
                .setSetVersion(1)
                .setSetDigest(fixture.setDigest())
                .setOutcome(switch (outcome) {
                    case "SINGLE_WINNER" -> ResultOutcome.RESULT_OUTCOME_SINGLE_WINNER;
                    case "DRAW" -> ResultOutcome.RESULT_OUTCOME_DRAW;
                    default -> ResultOutcome.RESULT_OUTCOME_MULTIPLE_WINNERS;
                })
                .setRuleVersion("equal-pool-v1")
                .addAllWinnerUserIds(winners.stream().map(UUID::toString).toList())
                .build();
    }

    private ListResultWinnersResponse listWinners(UUID resultId, String cursor) {
        var observer = new CapturingObserver<ListResultWinnersResponse>();
        results.listResultWinners(ListResultWinnersRequest.newBuilder()
                .setResultId(resultId.toString())
                .setCursor(cursor)
                .setLimit(10)
                .build(), observer);
        return observer.value;
    }

    private Fixture activeChallenge() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID joiner = UUID.randomUUID();
        var created = send("POST", "/api/v1/challenges/drafts", owner, UUID.randomUUID(), "{}");
        assertEquals(201, created.statusCode());
        String challengeId = mapper.readTree(created.body()).get("id").asString();
        var patched = send("PATCH", "/api/v1/challenges/" + challengeId + "/draft", owner,
                UUID.randomUUID(), """
                        {"version":1,"title":"Неделя отжиманий","description":"Тренируемся",
                         "stakeAmount":10,"durationDays":7}
                        """);
        assertEquals(200, patched.statusCode());
        when(coins.reserveStake(any(ReserveStakeRequest.class))).thenReturn(reserved());
        var published = send("POST", "/api/v1/challenges/" + challengeId + "/publish", owner,
                UUID.randomUUID(), "{\"version\":2}");
        assertEquals(200, published.statusCode());
        when(coins.beginStakeSet(any(BeginStakeSetRequest.class)))
                .thenReturn(BeginStakeSetResponse.newBuilder().build());
        when(coins.putStakeBatch(any(PutStakeBatchRequest.class)))
                .thenReturn(PutStakeBatchResponse.newBuilder().build());
        when(coins.finalizeStakeSet(any(FinalizeStakeSetRequest.class)))
                .thenReturn(FinalizeStakeSetResponse.newBuilder().build());
        when(coins.reserveStake(any(ReserveStakeRequest.class))).thenReturn(reserved());
        var joined = send("POST", "/api/v1/challenges/" + challengeId + "/joins", joiner,
                UUID.randomUUID(), "{}");
        assertEquals(200, joined.statusCode());
        assertEquals("ACTIVE", challengeStatus(challengeId));
        String setDigest = jdbc.queryForObject(
                "SELECT encode(digest, 'hex') FROM challenge_stake_sets WHERE challenge_id = ?",
                String.class, UUID.fromString(challengeId));
        return new Fixture(challengeId, owner, joiner, setDigest);
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

    private String participantStatus(String challengeId, UUID userId) {
        return jdbc.queryForObject("""
                SELECT status FROM challenge_participants WHERE challenge_id = ? AND user_id = ?
                """, String.class, UUID.fromString(challengeId), userId);
    }

    private long count(String sql, Object... args) {
        return java.util.Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args),
                "count query must return a value");
    }

    private static String reason(Throwable error) throws Exception {
        return StatusProto.fromThrowable(error).getDetailsList().getFirst()
                .unpack(ErrorInfo.class).getReason();
    }

    private static ReserveStakeResponse reserved() {
        return ReserveStakeResponse.newBuilder()
                .setOutcome(ReserveOutcome.RESERVE_OUTCOME_RESERVED)
                .setHoldId(UUID.randomUUID().toString())
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

    private record Fixture(String challengeId, UUID owner, UUID joiner, String setDigest) {
    }

    private static final class CapturingObserver<T> implements StreamObserver<T> {

        private T value;
        private Throwable error;

        @Override
        public void onNext(T next) {
            value = next;
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
        }

        @Override
        public void onCompleted() {
        }
    }
}
