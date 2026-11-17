package io.github.returnaction.fitness.coins.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.returnaction.fitness.coins.challenge.ChallengeResultClient;
import io.github.returnaction.fitness.coins.stake.BeginStakeSetCommand;
import io.github.returnaction.fitness.coins.stake.FinalizeStakeSetCommand;
import io.github.returnaction.fitness.coins.stake.PutStakeBatchCommand;
import io.github.returnaction.fitness.coins.stake.ReserveStakeCommand;
import io.github.returnaction.fitness.coins.stake.ReserveStakeResult;
import io.github.returnaction.fitness.coins.stake.SealedStake;
import io.github.returnaction.fitness.coins.stake.StakeDigest;
import io.github.returnaction.fitness.coins.stake.StakeReservationService;
import io.github.returnaction.fitness.coins.stake.StakeSetService;
import io.github.returnaction.fitness.coins.wallet.grpc.SecureGrpcServer;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class SettlementServiceIntegrationTest {

    private static final String EVENT_TYPE = "challenge.finished";
    private static final String PRODUCER = "challenge";
    private static final String RULE_VERSION = "equal-pool-v1";
    private static final long STAKE = 10;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean
    private SecureGrpcServer grpcServer;

    @MockitoBean
    private ChallengeResultClient challengeResults;

    @Autowired
    private ChallengeFinishedProcessor processor;

    @Autowired
    private StakeReservationService reservations;

    @Autowired
    private StakeSetService stakeSets;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearEventState() {
        jdbc.update("DELETE FROM coin_inbox");
        jdbc.update("DELETE FROM coin_outbox");
        jdbc.update("DELETE FROM coin_settlement_allocations");
        jdbc.update("DELETE FROM coin_settlement_hold_moves");
        jdbc.update("DELETE FROM coin_settlement_winners");
        jdbc.update("DELETE FROM coin_settlements");
    }

    @Test
    void settlesSingleWinnerAndWritesCompletionEvent() {
        Fixture fixture = sealedFixture();
        UUID resultId = UUID.randomUUID();
        String payload = event(UUID.randomUUID(), fixture, resultId, "SINGLE_WINNER", 1,
                SettlementDigest.toHex(SettlementDigest.winnersDigest(List.of(fixture.winner()))));
        when(challengeResults.listWinners(resultId)).thenReturn(List.of(fixture.winner()));

        assertThat(processor.handle(fixture.challengeId().toString(), payload))
                .isEqualTo(SettlementApplyResult.APPLIED);

        assertThat(available(fixture.winner())).isEqualTo(110L);
        assertThat(locked(fixture.winner())).isZero();
        assertThat(available(fixture.loser())).isEqualTo(90L);
        assertThat(locked(fixture.loser())).isZero();
        assertThat(holdStatus(fixture.winnerHold())).isEqualTo("SETTLED");
        assertThat(holdStatus(fixture.loserHold())).isEqualTo("SETTLED");
        assertThat(stakeSetState(fixture.challengeId())).isEqualTo("SETTLED");
        assertThat(settlementPhase(fixture.challengeId())).isEqualTo("COMPLETED");
        assertThat(settlementPool(fixture.challengeId())).isEqualTo(20L);
        assertThat(allocation(fixture.challengeId(), fixture.winner())).isEqualTo(20L);
        assertThat(count("""
                SELECT count(*) FROM coin_settlement_hold_moves
                WHERE settlement_id = (SELECT id FROM coin_settlements WHERE challenge_id = ?)
                """, fixture.challengeId())).isEqualTo(2L);
        assertThat(inboxCount()).isEqualTo(1L);
        assertThat(outboxCount()).isEqualTo(1L);

        JsonNode event = outboxEvent(fixture.challengeId());
        UUID outboxEventId = jdbc.queryForObject(
                "SELECT event_id FROM coin_outbox WHERE aggregate_id = ?", UUID.class, fixture.challengeId());
        assertThat(event.get("event_id").asString()).isEqualTo(outboxEventId.toString());
        assertThat(event.get("event_type").asString()).isEqualTo("coins.settlement_completed");
        assertThat(event.get("producer").asString()).isEqualTo("coins");
        assertThat(event.get("data").get("challengeId").asString()).isEqualTo(fixture.challengeId().toString());
        assertThat(event.get("data").get("resultId").asString()).isEqualTo(resultId.toString());
        assertThat(event.get("data").get("setVersion").asLong()).isEqualTo(1L);
        assertThat(event.get("data").get("ruleVersion").asString()).isEqualTo(RULE_VERSION);
    }

    @Test
    void settlesDrawAndReturnsEachStake() {
        Fixture fixture = sealedFixture();
        UUID resultId = UUID.randomUUID();
        List<UUID> winners = List.of(fixture.winner(), fixture.loser());
        String payload = event(UUID.randomUUID(), fixture, resultId, "DRAW", 2,
                SettlementDigest.toHex(SettlementDigest.winnersDigest(winners)));
        when(challengeResults.listWinners(resultId)).thenReturn(winners);

        assertThat(processor.handle(fixture.challengeId().toString(), payload))
                .isEqualTo(SettlementApplyResult.APPLIED);

        assertThat(available(fixture.winner())).isEqualTo(100L);
        assertThat(locked(fixture.winner())).isZero();
        assertThat(available(fixture.loser())).isEqualTo(100L);
        assertThat(locked(fixture.loser())).isZero();
        assertThat(allocation(fixture.challengeId(), fixture.winner())).isEqualTo(STAKE);
        assertThat(allocation(fixture.challengeId(), fixture.loser())).isEqualTo(STAKE);
        assertThat(outboxCount()).isEqualTo(1L);
    }

    @Test
    void quarantinesWinnerDigestOrMembershipMismatch() {
        Fixture fixture = sealedFixture();
        UUID resultId = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        String digestPayload = event(UUID.randomUUID(), fixture, resultId, "SINGLE_WINNER", 1,
                SettlementDigest.toHex(SettlementDigest.winnersDigest(List.of(unknown))));
        when(challengeResults.listWinners(resultId)).thenReturn(List.of(unknown));

        assertThat(processor.handle(fixture.challengeId().toString(), digestPayload))
                .isEqualTo(SettlementApplyResult.QUARANTINED);

        assertThat(settlementCount(fixture.challengeId())).isZero();
        assertThat(available(fixture.winner())).isEqualTo(90L);
        assertThat(locked(fixture.winner())).isEqualTo(STAKE);
        assertThat(inboxCount()).isZero();
        assertThat(outboxCount()).isZero();
    }

    @Test
    void quarantinesWinnerListThatDoesNotMatchTheEventDigest() {
        Fixture fixture = sealedFixture();
        UUID resultId = UUID.randomUUID();
        String payload = event(UUID.randomUUID(), fixture, resultId, "SINGLE_WINNER", 1,
                SettlementDigest.toHex(SettlementDigest.winnersDigest(List.of(fixture.winner()))));
        when(challengeResults.listWinners(resultId)).thenReturn(List.of(fixture.loser()));

        assertThat(processor.handle(fixture.challengeId().toString(), payload))
                .isEqualTo(SettlementApplyResult.QUARANTINED);
        assertThat(settlementCount(fixture.challengeId())).isZero();
        assertThat(available(fixture.winner())).isEqualTo(90L);
        assertThat(locked(fixture.loser())).isEqualTo(STAKE);
        assertThat(outboxCount()).isZero();
    }

    @Test
    void rejectsEventWithMismatchedSetDigest() {
        Fixture fixture = sealedFixture();
        String payload = event(UUID.randomUUID(), new Fixture(fixture.challengeId(), fixture.winner(),
                fixture.loser(), fixture.winnerHold(), fixture.loserHold(), "00".repeat(32)),
                UUID.randomUUID(), "SINGLE_WINNER", 1,
                SettlementDigest.toHex(SettlementDigest.winnersDigest(List.of(fixture.winner()))));

        assertThat(processor.handle(fixture.challengeId().toString(), payload))
                .isEqualTo(SettlementApplyResult.QUARANTINED);
        assertThat(settlementCount(fixture.challengeId())).isZero();
        assertThat(outboxCount()).isZero();
    }

    @Test
    void replaysSameEventAndAnotherEventForTheSameResult() {
        Fixture fixture = sealedFixture();
        UUID resultId = UUID.randomUUID();
        String winnerDigest = SettlementDigest.toHex(SettlementDigest.winnersDigest(List.of(fixture.winner())));
        UUID eventId = UUID.randomUUID();
        String payload = event(eventId, fixture, resultId, "SINGLE_WINNER", 1, winnerDigest);
        when(challengeResults.listWinners(resultId)).thenReturn(List.of(fixture.winner()));

        assertThat(processor.handle(fixture.challengeId().toString(), payload))
                .isEqualTo(SettlementApplyResult.APPLIED);
        assertThat(processor.handle(fixture.challengeId().toString(), payload))
                .isEqualTo(SettlementApplyResult.DUPLICATE);

        String secondPayload = event(UUID.randomUUID(), fixture, resultId, "SINGLE_WINNER", 1, winnerDigest);
        assertThat(processor.handle(fixture.challengeId().toString(), secondPayload))
                .isEqualTo(SettlementApplyResult.DUPLICATE);

        assertThat(available(fixture.winner())).isEqualTo(110L);
        assertThat(settlementCount(fixture.challengeId())).isEqualTo(1L);
        assertThat(outboxCount()).isEqualTo(1L);
        assertThat(inboxCount()).isEqualTo(2L);
    }

    @Test
    void quarantinesSameEventIdWithDifferentDigest() {
        Fixture fixture = sealedFixture();
        UUID resultId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String first = event(eventId, fixture, resultId, "SINGLE_WINNER", 1,
                SettlementDigest.toHex(SettlementDigest.winnersDigest(List.of(fixture.winner()))));
        when(challengeResults.listWinners(resultId)).thenReturn(List.of(fixture.winner()));
        assertThat(processor.handle(fixture.challengeId().toString(), first))
                .isEqualTo(SettlementApplyResult.APPLIED);

        String conflicting = event(eventId, fixture, resultId, "DRAW", 2,
                SettlementDigest.toHex(SettlementDigest.winnersDigest(
                        List.of(fixture.winner(), fixture.loser()))));
        assertThat(processor.handle(fixture.challengeId().toString(), conflicting))
                .isEqualTo(SettlementApplyResult.QUARANTINED);

        assertThat(available(fixture.winner())).isEqualTo(110L);
        assertThat(settlementCount(fixture.challengeId())).isEqualTo(1L);
        assertThat(outboxCount()).isEqualTo(1L);
    }

    private Fixture sealedFixture() {
        UUID challengeId = UUID.randomUUID();
        UUID winner = createWallet();
        UUID loser = createWallet();
        UUID winnerHold = reserve(winner, challengeId);
        UUID loserHold = reserve(loser, challengeId);
        List<SealedStake> stakes = List.of(
                new SealedStake(winnerHold, winner, STAKE),
                new SealedStake(loserHold, loser, STAKE));
        UUID sealId = UUID.randomUUID();
        stakeSets.begin(new BeginStakeSetCommand(sealId, challengeId, 1, stakes.size(),
                StakeDigest.toHex(StakeDigest.digest(stakes))));
        stakeSets.putBatch(new PutStakeBatchCommand(sealId, 0, stakes));
        stakeSets.finalizeSet(new FinalizeStakeSetCommand(sealId));
        return new Fixture(challengeId, winner, loser, winnerHold, loserHold,
                jdbc.queryForObject("SELECT encode(digest, 'hex') FROM coin_stake_sets WHERE challenge_id = ?",
                        String.class, challengeId));
    }

    private UUID createWallet() {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO coin_wallets (user_id, available_balance) VALUES (?, 100)", userId);
        return userId;
    }

    private UUID reserve(UUID userId, UUID challengeId) {
        ReserveStakeResult result = reservations.reserve(new ReserveStakeCommand(UUID.randomUUID(),
                userId, challengeId, STAKE));
        return result.holdId();
    }

    private String event(UUID eventId, Fixture fixture, UUID resultId, String outcome, int winnerCount,
                         String winnersDigest) {
        return """
                {"event_id":"%s","event_type":"%s","schema_version":1,
                 "occurred_at":"2026-11-17T10:00:00Z","producer":"%s","correlation_id":"%s",
                 "data":{"challengeId":"%s","resultId":"%s","setVersion":1,"setDigest":"%s",
                 "ruleVersion":"%s","outcome":"%s","winnerCount":%d,"winnersDigest":"%s"}}
                """.formatted(eventId, EVENT_TYPE, PRODUCER, UUID.randomUUID(), fixture.challengeId(),
                resultId, fixture.setDigest(), RULE_VERSION, outcome, winnerCount, winnersDigest);
    }

    private long available(UUID userId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT available_balance FROM coin_wallets WHERE user_id = ?", Long.class, userId));
    }

    private long locked(UUID userId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT locked_balance FROM coin_wallets WHERE user_id = ?", Long.class, userId));
    }

    private String holdStatus(UUID holdId) {
        return jdbc.queryForObject("SELECT status FROM coin_holds WHERE id = ?", String.class, holdId);
    }

    private String stakeSetState(UUID challengeId) {
        return jdbc.queryForObject("SELECT state FROM coin_stake_sets WHERE challenge_id = ?",
                String.class, challengeId);
    }

    private String settlementPhase(UUID challengeId) {
        return jdbc.queryForObject("SELECT phase FROM coin_settlements WHERE challenge_id = ?",
                String.class, challengeId);
    }

    private long settlementPool(UUID challengeId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT pool FROM coin_settlements WHERE challenge_id = ?", Long.class, challengeId));
    }

    private long allocation(UUID challengeId, UUID userId) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT a.amount FROM coin_settlement_allocations a
                JOIN coin_settlements s ON s.id = a.settlement_id
                WHERE s.challenge_id = ? AND a.user_id = ?
                """, Long.class, challengeId, userId));
    }

    private JsonNode outboxEvent(UUID challengeId) {
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM coin_outbox WHERE aggregate_id = ?", String.class, challengeId);
        return objectMapper.readTree(payload);
    }

    private long settlementCount(UUID challengeId) {
        return count("SELECT count(*) FROM coin_settlements WHERE challenge_id = ?", challengeId);
    }

    private long inboxCount() {
        return count("SELECT count(*) FROM coin_inbox WHERE consumer = ?", SettlementService.CONSUMER);
    }

    private long outboxCount() {
        return count("SELECT count(*) FROM coin_outbox WHERE event_type = 'coins.settlement_completed'");
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args),
                "count query must return a value");
    }

    private record Fixture(UUID challengeId, UUID winner, UUID loser, UUID winnerHold, UUID loserHold,
                           String setDigest) {
    }
}
