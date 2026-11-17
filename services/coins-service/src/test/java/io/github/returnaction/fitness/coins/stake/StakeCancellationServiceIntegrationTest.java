package io.github.returnaction.fitness.coins.stake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.coins.stake.exception.ActiveHoldsRemainException;
import io.github.returnaction.fitness.coins.stake.exception.OperationIdConflictException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetCancellationConflictException;
import io.github.returnaction.fitness.coins.stake.exception.StakeSetCancelledException;
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
class StakeCancellationServiceIntegrationTest {

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
    private StakeCancellationService cancellations;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void freezesAndCompletesAfterRelease() {
        UUID owner = createWallet(100);
        UUID otherUser = createWallet(100);
        UUID challengeId = UUID.randomUUID();
        UUID holdId = reserve(owner, challengeId, 30);
        UUID cancellationId = UUID.randomUUID();

        var freeze = new FreezeStakeSetCommand(cancellationId, challengeId);
        assertThat(cancellations.freeze(freeze)).isEqualTo(new FreezeStakeSetResult(cancellationId, challengeId));
        assertThat(stakeSetState(challengeId)).isEqualTo("CANCEL_DRAINING");

        var blocked = new ReserveStakeCommand(UUID.randomUUID(), otherUser, challengeId, 10);
        assertThatThrownBy(() -> reservations.reserve(blocked))
                .isInstanceOf(StakeSetCancelledException.class);

        releases.release(new ReleaseStakeCommand(UUID.randomUUID(), holdId, challengeId,
                ReleaseReason.CHALLENGE_CANCELLED));
        var complete = new CompleteStakeCancellationCommand(cancellationId, challengeId);
        assertThat(cancellations.complete(complete))
                .isEqualTo(new CompleteStakeCancellationResult(cancellationId, challengeId));
        assertThat(stakeSetState(challengeId)).isEqualTo("CANCELLED");
        assertThat(wallet(owner)).containsExactly(100L, 0L);

        assertThat(cancellations.freeze(freeze)).isEqualTo(new FreezeStakeSetResult(cancellationId, challengeId));
        assertThat(cancellations.complete(complete))
                .isEqualTo(new CompleteStakeCancellationResult(cancellationId, challengeId));
        assertThat(stakeSetState(challengeId)).isEqualTo("CANCELLED");
    }

    @Test
    void completeRejectsActiveHolds() {
        UUID owner = createWallet(100);
        UUID challengeId = UUID.randomUUID();
        reserve(owner, challengeId, 30);
        UUID cancellationId = UUID.randomUUID();
        cancellations.freeze(new FreezeStakeSetCommand(cancellationId, challengeId));

        var complete = new CompleteStakeCancellationCommand(cancellationId, challengeId);
        assertThatThrownBy(() -> cancellations.complete(complete))
                .isInstanceOf(ActiveHoldsRemainException.class);
        assertThat(stakeSetState(challengeId)).isEqualTo("CANCEL_DRAINING");
    }

    @Test
    void freezeIsIdempotentAndRejectsForeignCancellationId() {
        UUID owner = createWallet(100);
        UUID challengeId = UUID.randomUUID();
        reserve(owner, challengeId, 30);
        UUID cancellationId = UUID.randomUUID();
        var freeze = new FreezeStakeSetCommand(cancellationId, challengeId);
        cancellations.freeze(freeze);

        assertThat(cancellations.freeze(freeze)).isEqualTo(new FreezeStakeSetResult(cancellationId, challengeId));

        var foreign = new FreezeStakeSetCommand(UUID.randomUUID(), challengeId);
        assertThatThrownBy(() -> cancellations.freeze(foreign))
                .isInstanceOf(OperationIdConflictException.class);
        assertThat(stakeSetState(challengeId)).isEqualTo("CANCEL_DRAINING");
    }

    @Test
    void completeRequiresFreeze() {
        UUID owner = createWallet(100);
        UUID challengeId = UUID.randomUUID();
        reserve(owner, challengeId, 30);

        var complete = new CompleteStakeCancellationCommand(UUID.randomUUID(), challengeId);
        assertThatThrownBy(() -> cancellations.complete(complete))
                .isInstanceOf(StakeSetCancellationConflictException.class);
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

    private String stakeSetState(UUID challengeId) {
        return jdbc.queryForObject("SELECT state FROM coin_stake_sets WHERE challenge_id = ?", String.class,
                challengeId);
    }

    private List<Long> wallet(UUID userId) {
        return jdbc.query("SELECT available_balance, locked_balance FROM coin_wallets WHERE user_id = ?",
                (resultSet, rowNumber) -> List.of(
                        resultSet.getLong("available_balance"),
                        resultSet.getLong("locked_balance")),
                userId).getFirst();
    }
}
