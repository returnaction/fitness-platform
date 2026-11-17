package io.github.returnaction.fitness.coins.dailyreward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.coins.dailyreward.dto.DailyRewardClaimResponse;
import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardClaimEntity;
import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardOutcome;
import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardRequestEntity;
import io.github.returnaction.fitness.coins.dailyreward.repository.DailyRewardClaimRepository;
import io.github.returnaction.fitness.coins.dailyreward.repository.DailyRewardRequestRepository;
import io.github.returnaction.fitness.coins.dailyreward.service.DailyRewardClaimTransactionService;
import io.github.returnaction.fitness.coins.wallet.entity.LedgerEntryEntity;
import io.github.returnaction.fitness.coins.wallet.entity.LedgerEntryType;
import io.github.returnaction.fitness.coins.wallet.grpc.SecureGrpcServer;
import io.github.returnaction.fitness.coins.wallet.repository.LedgerEntryRepository;
import io.github.returnaction.fitness.coins.wallet.repository.WalletRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import java.util.Arrays;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class DailyRewardPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @MockitoBean
    private SecureGrpcServer managedGrpcServer;

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private LedgerEntryRepository ledgerEntries;

    @Autowired
    private DailyRewardClaimRepository claims;

    @Autowired
    private DailyRewardRequestRepository requests;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DailyRewardClaimTransactionService dailyRewardClaimTransactionService;

    @Test
    void rejectsUpdatesAndDeletesOfLedgerClaimsAndSavedResponses() {
        UUID userId = UUID.randomUUID();
        transactions.executeWithoutResult(_ -> wallets.insertIfAbsent(userId));
        dailyRewardClaimTransactionService.claimDailyReward(userId, UUID.randomUUID());
        for (String table : List.of("coin_ledger_entries", "daily_reward_requests")) {
            assertSqlRejected("UPDATE " + table + " SET created_at = created_at WHERE user_id = :userId",
                userId, "P0001");
            assertSqlRejected("DELETE FROM " + table + " WHERE user_id = :userId", userId, "P0001");
        }
        assertSqlRejected("UPDATE daily_reward_claims SET claimed_at = claimed_at - INTERVAL '24 hours' WHERE user_id = :userId",
            userId, "P0001");
        assertSqlRejected("DELETE FROM daily_reward_claims WHERE user_id = :userId", userId, "P0001");
        assertThat(reconciliation(userId)).containsExactly(10L, 10L, 0L, 0L, 1L);
    }

    @Test
    void enforcesRequiredFieldsUniqueKeysChecksAndForeignKeys() {
        UUID userId = UUID.randomUUID();
        transactions.executeWithoutResult(_ -> wallets.insertIfAbsent(userId));
        dailyRewardClaimTransactionService.claimDailyReward(userId, UUID.randomUUID());
        String newId = UUID.randomUUID().toString();
        String missingId = UUID.randomUUID().toString();

        for (String table : List.of("coin_ledger_entries", "daily_reward_claims", "daily_reward_requests")) {
            assertCloneRejected(table, userId, "{}", "23505"); // Повтор первичного ключа.
            assertCloneRejected(table, userId, "{\"id\":\"" + newId + "\"}", "23505"); // Повтор бизнес-ключа.
        }
        for (String field : List.of("id", "user_id", "operation_id", "entry_index", "entry_type",
            "available_delta", "locked_delta", "created_at")) {
            assertCloneRejected("coin_ledger_entries", userId, "{\"" + field + "\":null}", "23502");
        }
        for (String field : List.of("id", "user_id", "claimed_at", "reward_amount", "ledger_entry_id")) {
            assertCloneRejected("daily_reward_claims", userId, "{\"" + field + "\":null}", "23502");
        }
        for (String field : List.of("id", "user_id", "idempotency_key", "outcome", "claim_id",
            "response_snapshot", "created_at")) {
            assertCloneRejected("daily_reward_requests", userId, "{\"" + field + "\":null}", "23502");
        }
        for (String changes : List.of("{\"entry_index\":-1}", "{\"entry_type\":\"UNKNOWN\"}",
            "{\"available_delta\":0,\"locked_delta\":0}")) {
            assertCloneRejected("coin_ledger_entries", userId, changes, "23514");
        }
        for (int amount : List.of(0, -1)) {
            assertCloneRejected("daily_reward_claims", userId, "{\"reward_amount\":" + amount + "}", "23514");
        }
        assertCloneRejected("daily_reward_requests", userId, "{\"outcome\":\"UNKNOWN\"}", "23514");

        String ledgerBase = "\"id\":\"" + newId + "\",\"operation_id\":\"" + newId + "\",";
        String claimBase = "\"id\":\"" + newId + "\",\"ledger_entry_id\":\"" + missingId + "\",";
        String requestBase = "\"id\":\"" + newId + "\",\"idempotency_key\":\"" + newId + "\",";
        assertCloneRejected("coin_ledger_entries", userId, "{" + ledgerBase + "\"user_id\":\"" + missingId + "\"}", "23503");
        assertCloneRejected("coin_ledger_entries", userId, "{" + ledgerBase + "\"reverses_entry_id\":\"" + missingId + "\"}", "23503");
        assertCloneRejected("daily_reward_claims", userId, "{" + claimBase + "\"user_id\":\"" + missingId + "\"}", "23503");
        assertCloneRejected("daily_reward_claims", userId, "{" + claimBase + "\"user_id\":\"" + userId + "\"}", "23503");
        assertCloneRejected("daily_reward_requests", userId, "{" + requestBase + "\"user_id\":\"" + missingId + "\"}", "23503");
        assertCloneRejected("daily_reward_requests", userId, "{" + requestBase + "\"claim_id\":\"" + missingId + "\"}", "23503");
        assertSqlRejected("DELETE FROM coin_wallets WHERE user_id = :userId", userId, "23503");
        assertSqlRejected("UPDATE coin_wallets SET available_balance = -1 WHERE user_id = :userId", userId, "23514");
        assertSqlRejected("UPDATE coin_wallets SET locked_balance = -1 WHERE user_id = :userId", userId, "23514");
    }

    private void assertCloneRejected(String table, UUID userId, String changes, String sqlState) {
        // Имена таблиц заданы только тестом. Копируем валидную строку, меняя проверяемые поля.
        assertDatabaseRejection(() -> transactions.executeWithoutResult(_ -> entityManager.createNativeQuery(
                "INSERT INTO " + table + " SELECT (jsonb_populate_record(NULL::" + table
                    + ", to_jsonb(source) || CAST(:changes AS jsonb))).* FROM " + table
                    + " source WHERE user_id = :userId")
            .setParameter("changes", changes).setParameter("userId", userId).executeUpdate()), sqlState);
    }

    private void assertSqlRejected(String sql, UUID userId, String sqlState) {
        assertDatabaseRejection(() -> transactions.executeWithoutResult(_ -> entityManager.createNativeQuery(sql)
            .setParameter("userId", userId).executeUpdate()), sqlState);
    }

    private void assertDatabaseRejection(Runnable operation, String sqlState) {
        assertThatThrownBy(operation::run).satisfies(failure -> {
            Throwable cause = failure;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertThat(cause).isInstanceOf(SQLException.class);
            assertThat(((SQLException) cause).getSQLState()).isEqualTo(sqlState);
        });
    }

    @Test
    void reconcilesBothBalancesAfterRewardAndRepeatedRequests() {
        UUID userId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        transactions.executeWithoutResult(_ -> wallets.insertIfAbsent(userId));
        assertThat(reconciliation(userId)).containsExactly(0L, 0L, 0L, 0L, 0L);

        var granted = dailyRewardClaimTransactionService.claimDailyReward(userId, key);
        assertThat(granted.granted()).isTrue();
        assertThat(reconciliation(userId)).containsExactly(10L, 10L, 0L, 0L, 1L);

        assertThat(dailyRewardClaimTransactionService.claimDailyReward(userId, key)).isEqualTo(granted);
        assertThat(dailyRewardClaimTransactionService.claimDailyReward(userId, UUID.randomUUID()).granted()).isFalse();
        assertThat(reconciliation(userId)).containsExactly(10L, 10L, 0L, 0L, 1L);

        // История другого пользователя не должна попадать в сверку этого кошелька.
        UUID otherUser = UUID.randomUUID();
        transactions.executeWithoutResult(_ -> wallets.insertIfAbsent(otherUser));
        dailyRewardClaimTransactionService.claimDailyReward(otherUser, UUID.randomUUID());
        assertThat(reconciliation(userId)).containsExactly(10L, 10L, 0L, 0L, 1L);
    }

    @Test
    void detectsArtificialDriftInAvailableAndLockedBalances() {
        UUID userId = UUID.randomUUID();
        transactions.executeWithoutResult(_ -> wallets.insertIfAbsent(userId));
        dailyRewardClaimTransactionService.claimDailyReward(userId, UUID.randomUUID());

        // Только тестовая БД: имитируем изменение баланса без соответствующих записей ledger.
        transactions.executeWithoutResult(_ -> entityManager.createNativeQuery("""
                UPDATE coin_wallets SET available_balance = 13, locked_balance = 2
                WHERE user_id = :userId
                """).setParameter("userId", userId).executeUpdate());

        var result = reconciliation(userId);
        assertThat(result).containsExactly(13L, 10L, 2L, 0L, 1L);
        assertThat(result.get(0) - result.get(1)).isEqualTo(3);
        assertThat(result.get(2) - result.get(3)).isEqualTo(2);
    }

    // Начальный кошелёк в этих тестах нулевой: ожидаемые балансы равны суммам всех движений ledger.
    // Возвращаем: доступный факт/ожидание, резерв факт/ожидание, количество записей ledger.
    private List<Long> reconciliation(UUID userId) {
        return transactions.execute(_ -> {
            Object[] row = (Object[]) entityManager.createNativeQuery("""
                    SELECT wallet.available_balance, COALESCE(SUM(entry.available_delta), 0),
                           wallet.locked_balance, COALESCE(SUM(entry.locked_delta), 0), COUNT(entry.id)
                    FROM coin_wallets wallet
                    LEFT JOIN coin_ledger_entries entry ON entry.user_id = wallet.user_id
                    WHERE wallet.user_id = :userId
                    GROUP BY wallet.user_id, wallet.available_balance, wallet.locked_balance
                    """).setParameter("userId", userId).getSingleResult();
            return Arrays.stream(row).map(value -> ((Number) value).longValue()).toList();
        });
    }

    @Test
    void rollsBackBalanceLedgerClaimAndRequestTogether() {
        UUID userId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        transactions.executeWithoutResult(_ -> wallets.insertIfAbsent(userId));

        assertThatThrownBy(() -> transactions.executeWithoutResult(_ -> {
            dailyRewardClaimTransactionService.claimDailyReward(userId, idempotencyKey);
            entityManager.flush();
            throw new IllegalStateException("Simulated failure after reward writes");
        })).isInstanceOf(IllegalStateException.class)
            .hasMessage("Simulated failure after reward writes");

        assertThat(wallets.findById(userId).orElseThrow().getAvailableBalance()).isZero();
        assertThat(claims.findFirstByUserIdOrderByClaimedAtDesc(userId)).isEmpty();
        assertThat(requests.findByUserIdAndIdempotencyKey(userId, idempotencyKey)).isEmpty();
        transactions.executeWithoutResult(_ -> {
            Number ledgerCount = (Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM coin_ledger_entries WHERE user_id = :userId")
                .setParameter("userId", userId)
                .getSingleResult();
            assertThat(ledgerCount.longValue()).isZero();
        });
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void storesResponseSnapshotAsJsonObjectAndReadsItBack() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID idempotencyKey = UUID.fromString("3f1b0d9e-6a99-4b30-9c5e-1e2a9f1d0c77");
        Instant now = Instant.parse("2026-11-16T12:34:56Z");
        Instant next = Instant.parse("2026-11-17T12:34:56Z");
        LocalDate claimDate = LocalDate.parse("2026-11-16");

        transactions.executeWithoutResult(_ -> wallets.insertIfAbsent(userId));

        ledgerEntries.save(new LedgerEntryEntity(ledgerId, userId, claimId, 0, LedgerEntryType.DAILY_REWARD_CREDIT,
            10, 0, null, null, now));
        claims.save(new DailyRewardClaimEntity(claimId, userId, now, 10, ledgerId));

        var snapshot = new DailyRewardClaimResponse(10, 10, 0, true, next, now);
        requests.save(new DailyRewardRequestEntity(requestId, userId, idempotencyKey, DailyRewardOutcome.GRANTED,
            claimId, snapshot, now));

        var stored = requests.findByUserIdAndIdempotencyKey(userId, idempotencyKey).orElseThrow();

        assertThat(stored.getResponseSnapshot()).isEqualTo(snapshot);
        assertThat(stored.getOutcome()).isEqualTo(DailyRewardOutcome.GRANTED);
        assertThat(stored.getClaimId()).isEqualTo(claimId);

        transactions.executeWithoutResult(_ -> {
            Object[] row = (Object[]) entityManager.createNativeQuery("""
                    SELECT jsonb_typeof(response_snapshot),
                           response_snapshot ->> 'claimedAmount',
                           idempotency_key
                    FROM daily_reward_requests
                    WHERE id = :id
                    """)
                .setParameter("id", requestId)
                .getSingleResult();
            assertThat(row[0]).isEqualTo("object");
            assertThat(row[1]).isEqualTo("10");
            assertThat(row[2]).isEqualTo(idempotencyKey);
        });
    }

    @Test
    void ledgerIsAppendOnly() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-16T12:34:56Z");

        transactions.executeWithoutResult(_ -> wallets.insertIfAbsent(userId));
        ledgerEntries.save(new LedgerEntryEntity(ledgerId, userId, UUID.randomUUID(), 0,
            LedgerEntryType.DAILY_REWARD_CREDIT, 10, 0, null, null, now));

        assertThatThrownBy(() -> transactions.executeWithoutResult(_ ->
            entityManager.createNativeQuery("""
                    UPDATE coin_ledger_entries
                    SET available_delta = 11
                    WHERE id = :id
                    """)
                .setParameter("id", ledgerId)
                .executeUpdate()))
            .isInstanceOf(PersistenceException.class);
    }
}
