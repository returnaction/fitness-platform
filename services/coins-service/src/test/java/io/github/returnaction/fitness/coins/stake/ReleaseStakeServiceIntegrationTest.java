package io.github.returnaction.fitness.coins.stake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.coins.stake.exception.HoldChallengeMismatchException;
import io.github.returnaction.fitness.coins.stake.exception.HoldNotReleasableException;
import io.github.returnaction.fitness.coins.stake.exception.HoldNotFoundException;
import io.github.returnaction.fitness.coins.stake.exception.OperationIdConflictException;
import io.github.returnaction.fitness.coins.wallet.grpc.SecureGrpcServer;
import java.util.List;
import java.util.UUID;
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

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ReleaseStakeServiceIntegrationTest {

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

    @Autowired
    private StakeReservationService reservations;

    @Autowired
    private ReleaseStakeService releases;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void releasesHoldExactlyOnceAndReplaysTheSameOperation() {
        UUID userId = createWallet(100);
        UUID challengeId = UUID.randomUUID();
        UUID holdId = reserve(userId, challengeId, 30);
        UUID releaseOperationId = UUID.randomUUID();
        var command = new ReleaseStakeCommand(releaseOperationId, holdId, challengeId,
                ReleaseReason.JOIN_NOT_CONFIRMED);

        ReleaseStakeResult result = releases.release(command);

        assertThat(result.outcome()).isEqualTo(ReleaseOutcome.RELEASED);
        assertThat(result.holdId()).isEqualTo(holdId);
        assertThat(wallet(userId)).containsExactly(100L, 0L);
        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(count("""
                SELECT count(*) FROM coin_ledger_entries
                WHERE operation_id = ? AND type = 'RELEASE'
                  AND available_delta = 30 AND locked_delta = -30
                """, releaseOperationId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM coin_stake_operations WHERE kind = 'RELEASE' AND id = ?",
                releaseOperationId)).isEqualTo(1);

        assertThat(releases.release(command)).isEqualTo(result);
        assertThat(wallet(userId)).containsExactly(100L, 0L);
        assertThat(count("SELECT count(*) FROM coin_ledger_entries WHERE operation_id = ?", releaseOperationId))
                .isEqualTo(1);
    }

    @Test
    void rejectsSecondReleaseWithDifferentOperationId() {
        UUID userId = createWallet(100);
        UUID challengeId = UUID.randomUUID();
        UUID holdId = reserve(userId, challengeId, 30);
        UUID firstId = UUID.randomUUID();
        releases.release(new ReleaseStakeCommand(firstId, holdId, challengeId, ReleaseReason.JOIN_NOT_CONFIRMED));

        var second = new ReleaseStakeCommand(UUID.randomUUID(), holdId, challengeId,
                ReleaseReason.CHALLENGE_CANCELLED);
        assertThatThrownBy(() -> releases.release(second))
                .isInstanceOf(HoldNotReleasableException.class);
        assertThat(wallet(userId)).containsExactly(100L, 0L);
        assertThat(count("SELECT count(*) FROM coin_ledger_entries WHERE type = 'RELEASE' AND user_id = ?",
                userId)).isEqualTo(1);
    }

    @Test
    void rejectsHoldFromAnotherChallenge() {
        UUID userId = createWallet(100);
        UUID challengeId = UUID.randomUUID();
        UUID holdId = reserve(userId, challengeId, 30);

        var wrongChallenge = new ReleaseStakeCommand(UUID.randomUUID(), holdId, UUID.randomUUID(),
                ReleaseReason.CHALLENGE_CANCELLED);
        assertThatThrownBy(() -> releases.release(wrongChallenge))
                .isInstanceOf(HoldChallengeMismatchException.class);
        assertThat(wallet(userId)).containsExactly(70L, 30L);
        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
    }

    @Test
    void rejectsUnknownHold() {
        var command = new ReleaseStakeCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ReleaseReason.JOIN_NOT_CONFIRMED);

        assertThatThrownBy(() -> releases.release(command))
                .isInstanceOf(HoldNotFoundException.class);
    }

    @Test
    void rejectsTheSameReleaseOperationWithDifferentReason() {
        UUID userId = createWallet(100);
        UUID challengeId = UUID.randomUUID();
        UUID holdId = reserve(userId, challengeId, 30);
        UUID releaseOperationId = UUID.randomUUID();
        releases.release(new ReleaseStakeCommand(releaseOperationId, holdId, challengeId,
                ReleaseReason.JOIN_NOT_CONFIRMED));

        var changedReason = new ReleaseStakeCommand(releaseOperationId, holdId, challengeId,
                ReleaseReason.CHALLENGE_CANCELLED);
        assertThatThrownBy(() -> releases.release(changedReason))
                .isInstanceOf(OperationIdConflictException.class);
    }

    private UUID createWallet(long available) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO coin_wallets (user_id, available_balance) VALUES (?, ?)", userId, available);
        return userId;
    }

    private UUID reserve(UUID userId, UUID challengeId, long amount) {
        return reservations.reserve(new ReserveStakeCommand(UUID.randomUUID(), userId, challengeId, amount))
                .holdId();
    }

    private String holdStatus(UUID holdId) {
        return jdbc.queryForObject("SELECT status FROM coin_holds WHERE id = ?", String.class, holdId);
    }

    private List<Long> wallet(UUID userId) {
        return jdbc.query("SELECT available_balance, locked_balance FROM coin_wallets WHERE user_id = ?",
                (resultSet, rowNumber) -> List.of(
                        resultSet.getLong("available_balance"),
                        resultSet.getLong("locked_balance")),
                userId).getFirst();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
