package io.github.returnaction.fitness.challenge.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class SettlementCompletedConsumerIntegrationTest {

    private static final String EVENT_TYPE = "coins.settlement_completed";
    private static final String PRODUCER = "coins";
    private static final String RULE_VERSION = "equal-pool-v1";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private SettlementCompletedProcessor processor;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearInboxAndProjection() {
        jdbc.update("DELETE FROM challenge_inbox");
        jdbc.update("DELETE FROM challenge_settlement_status");
    }

    @Test
    void appliesCompletedSettlementAndIgnoresReplay() {
        Fixture fixture = finalResult();
        UUID eventId = UUID.randomUUID();
        UUID settlementId = UUID.randomUUID();
        String payload = event(eventId, fixture, settlementId);

        assertThat(processor.apply(fixture.challengeId().toString(), payload))
                .isEqualTo(SettlementApplyResult.APPLIED);
        assertThat(settlementState(fixture.challengeId())).isEqualTo("COMPLETED");
        assertThat(settlementId(fixture.challengeId())).isEqualTo(settlementId);
        assertThat(settlementSourceEvent(fixture.challengeId())).isEqualTo(eventId);
        assertThat(inboxCount()).isEqualTo(1L);

        assertThat(processor.apply(fixture.challengeId().toString(), payload))
                .isEqualTo(SettlementApplyResult.DUPLICATE);
        assertThat(settlementId(fixture.challengeId())).isEqualTo(settlementId);
        assertThat(inboxCount()).isEqualTo(1L);
    }

    @Test
    void quarantinesSameEventIdWithDifferentDigest() {
        Fixture fixture = finalResult();
        UUID eventId = UUID.randomUUID();
        UUID firstSettlement = UUID.randomUUID();
        UUID secondSettlement = UUID.randomUUID();

        assertThat(processor.apply(fixture.challengeId().toString(),
                event(eventId, fixture, firstSettlement))).isEqualTo(SettlementApplyResult.APPLIED);
        assertThat(processor.apply(fixture.challengeId().toString(),
                event(eventId, fixture, secondSettlement))).isEqualTo(SettlementApplyResult.QUARANTINED);

        assertThat(settlementId(fixture.challengeId())).isEqualTo(firstSettlement);
        assertThat(inboxCount()).isEqualTo(1L);
    }

    @Test
    void quarantinesAnotherSettlementForTheSameChallenge() {
        Fixture fixture = finalResult();
        UUID firstSettlement = UUID.randomUUID();
        UUID secondSettlement = UUID.randomUUID();

        assertThat(processor.apply(fixture.challengeId().toString(),
                event(UUID.randomUUID(), fixture, firstSettlement)))
                .isEqualTo(SettlementApplyResult.APPLIED);
        assertThat(processor.apply(fixture.challengeId().toString(),
                event(UUID.randomUUID(), fixture, secondSettlement)))
                .isEqualTo(SettlementApplyResult.QUARANTINED);

        assertThat(settlementId(fixture.challengeId())).isEqualTo(firstSettlement);
        assertThat(inboxCount()).isEqualTo(1L);
    }

    @Test
    void doesNotApplyEventWhoseResultIsUnknown() {
        Fixture fixture = finalResult();
        UUID eventId = UUID.randomUUID();
        String payload = event(eventId, new Fixture(fixture.challengeId(), UUID.randomUUID()),
                UUID.randomUUID());

        assertThatThrownBy(() -> processor.apply(fixture.challengeId().toString(), payload))
                .isInstanceOf(SettlementNotReadyException.class);

        assertThat(settlementCount(fixture.challengeId())).isZero();
        assertThat(inboxCount()).isZero();
    }

    @Test
    void doesNotApplyEventThatDoesNotMatchTheFinalResult() {
        Fixture fixture = finalResult();
        String payload = event(UUID.randomUUID(), fixture, UUID.randomUUID(), 2);

        assertThat(processor.apply(fixture.challengeId().toString(), payload))
                .isEqualTo(SettlementApplyResult.QUARANTINED);

        assertThat(settlementCount(fixture.challengeId())).isZero();
        assertThat(inboxCount()).isZero();
    }

    private Fixture finalResult() {
        UUID challengeId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO challenges (id, creator_user_id, title, mode, status, stake_amount,
                    min_participants, max_participants, visibility, start_mode, started_at, duration_days)
                VALUES (?, ?, 'Settlement test', 'HEAD_TO_HEAD', 'FINISHED', 10, 2, 2, 'PUBLIC',
                    'WHEN_FULL', CURRENT_TIMESTAMP, 7)
                """, challengeId, UUID.randomUUID());
        jdbc.update("""
                INSERT INTO challenge_stake_sets (challenge_id, version, state, seal_operation_id, digest)
                VALUES (?, 1, 'SEALED', ?, ?)
                """, challengeId, UUID.randomUUID(), new byte[32]);
        jdbc.update("""
                INSERT INTO challenge_results (id, challenge_id, set_version, outcome, rule_version,
                    winner_count, winners_digest)
                VALUES (?, ?, 1, 'SINGLE_WINNER', ?, 1, ?)
                """, resultId, challengeId, RULE_VERSION, new byte[32]);
        return new Fixture(challengeId, resultId);
    }

    private String event(UUID eventId, Fixture fixture, UUID settlementId) {
        return event(eventId, fixture, settlementId, 1);
    }

    private String event(UUID eventId, Fixture fixture, UUID settlementId, long setVersion) {
        return """
                {"event_id":"%s","event_type":"%s","schema_version":1,
                 "occurred_at":"2026-11-17T10:00:00Z","producer":"%s","correlation_id":"%s",
                 "data":{"settlementId":"%s","challengeId":"%s","resultId":"%s","setVersion":%d,
                 "ruleVersion":"%s","completedAt":"2026-11-17T10:00:00Z"}}
                """.formatted(eventId, EVENT_TYPE, PRODUCER, UUID.randomUUID(), settlementId,
                fixture.challengeId(), fixture.resultId(), setVersion, RULE_VERSION);
    }

    private String settlementState(UUID challengeId) {
        return jdbc.queryForObject(
                "SELECT state FROM challenge_settlement_status WHERE challenge_id = ?",
                String.class, challengeId);
    }

    private UUID settlementId(UUID challengeId) {
        return jdbc.queryForObject(
                "SELECT settlement_id FROM challenge_settlement_status WHERE challenge_id = ?",
                UUID.class, challengeId);
    }

    private UUID settlementSourceEvent(UUID challengeId) {
        return jdbc.queryForObject(
                "SELECT source_event_id FROM challenge_settlement_status WHERE challenge_id = ?",
                UUID.class, challengeId);
    }

    private long settlementCount(UUID challengeId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM challenge_settlement_status WHERE challenge_id = ?",
                Long.class, challengeId));
    }

    private long inboxCount() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM challenge_inbox WHERE consumer = ?",
                Long.class, SettlementCompletedProcessor.CONSUMER));
    }

    private record Fixture(UUID challengeId, UUID resultId) {
    }
}
