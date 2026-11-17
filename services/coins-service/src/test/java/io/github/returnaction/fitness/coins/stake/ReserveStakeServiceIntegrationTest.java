package io.github.returnaction.fitness.coins.stake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.coins.stake.exception.OperationIdConflictException;
import io.github.returnaction.fitness.coins.stake.exception.WalletNotFoundException;
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
class ReserveStakeServiceIntegrationTest {

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
    private StakeReservationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void reservesStakeAndReplaysTheSameOperation() {
        UUID userId = createWallet(100);
        UUID challengeId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        var command = new ReserveStakeCommand(operationId, userId, challengeId, 30);

        ReserveStakeResult result = service.reserve(command);

        assertThat(result.outcome()).isEqualTo(ReserveOutcome.RESERVED);
        assertThat(result.holdId()).isNotNull();
        assertThat(wallet(userId)).containsExactly(70L, 30L);
        assertThat(count("""
                SELECT count(*) FROM coin_holds
                WHERE challenge_id = ? AND user_id = ? AND status = 'ACTIVE' AND amount = 30
                """, challengeId, userId)).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM coin_ledger_entries
                WHERE operation_id = ? AND type = 'RESERVE'
                  AND available_delta = -30 AND locked_delta = 30
                """, operationId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM coin_stake_operations WHERE kind = 'RESERVE' AND id = ?",
                operationId)).isEqualTo(1);

        ReserveStakeResult replay = service.reserve(command);
        assertThat(replay).isEqualTo(result);
        assertThat(wallet(userId)).containsExactly(70L, 30L);
        assertThat(count("SELECT count(*) FROM coin_holds WHERE challenge_id = ?", challengeId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM coin_ledger_entries WHERE operation_id = ?", operationId))
                .isEqualTo(1);
    }

    @Test
    void rejectsTheSameOperationWithDifferentPayload() {
        UUID userId = createWallet(100);
        UUID operationId = UUID.randomUUID();
        service.reserve(new ReserveStakeCommand(operationId, userId, UUID.randomUUID(), 10));

        var conflicting = new ReserveStakeCommand(operationId, userId, UUID.randomUUID(), 20);
        assertThatThrownBy(() -> service.reserve(conflicting))
                .isInstanceOf(OperationIdConflictException.class);
        assertThat(wallet(userId)).containsExactly(90L, 10L);
    }

    @Test
    void returnsInsufficientFundsWithoutBalanceChange() {
        UUID userId = createWallet(10);
        UUID operationId = UUID.randomUUID();
        var command = new ReserveStakeCommand(operationId, userId, UUID.randomUUID(), 30);

        ReserveStakeResult result = service.reserve(command);

        assertThat(result.outcome()).isEqualTo(ReserveOutcome.INSUFFICIENT_FUNDS);
        assertThat(result.holdId()).isNull();
        assertThat(wallet(userId)).containsExactly(10L, 0L);
        assertThat(count("SELECT count(*) FROM coin_holds WHERE user_id = ?", userId)).isZero();
        assertThat(service.reserve(command)).isEqualTo(result);
    }

    @Test
    void returnsActiveHoldExistsForSecondReserve() {
        UUID userId = createWallet(100);
        UUID challengeId = UUID.randomUUID();
        service.reserve(new ReserveStakeCommand(UUID.randomUUID(), userId, challengeId, 10));

        ReserveStakeResult second = service.reserve(
                new ReserveStakeCommand(UUID.randomUUID(), userId, challengeId, 10));

        assertThat(second.outcome()).isEqualTo(ReserveOutcome.ACTIVE_HOLD_EXISTS);
        assertThat(wallet(userId)).containsExactly(90L, 10L);
        assertThat(count("SELECT count(*) FROM coin_holds WHERE user_id = ?", userId)).isEqualTo(1);
    }

    @Test
    void rejectsMissingWalletWithoutCreatingRows() {
        UUID userId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        var command = new ReserveStakeCommand(UUID.randomUUID(), userId, challengeId, 10);

        assertThatThrownBy(() -> service.reserve(command))
                .isInstanceOf(WalletNotFoundException.class);
        assertThat(count("SELECT count(*) FROM coin_holds WHERE user_id = ?", userId)).isZero();
        assertThat(count("SELECT count(*) FROM coin_stake_sets WHERE challenge_id = ?", challengeId)).isZero();
    }

    private UUID createWallet(long available) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO coin_wallets (user_id, available_balance) VALUES (?, ?)", userId, available);
        return userId;
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
