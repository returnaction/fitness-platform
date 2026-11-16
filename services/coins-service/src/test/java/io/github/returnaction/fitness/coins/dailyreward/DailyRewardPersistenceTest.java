package io.github.returnaction.fitness.coins.dailyreward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.coins.dailyreward.dto.DailyRewardClaimResponse;
import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardClaimEntity;
import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardOutcome;
import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardRequestEntity;
import io.github.returnaction.fitness.coins.dailyreward.repository.DailyRewardClaimRepository;
import io.github.returnaction.fitness.coins.dailyreward.repository.DailyRewardRequestRepository;
import io.github.returnaction.fitness.coins.wallet.entity.LedgerEntryEntity;
import io.github.returnaction.fitness.coins.wallet.entity.LedgerEntryType;
import io.github.returnaction.fitness.coins.wallet.grpc.SecureGrpcServer;
import io.github.returnaction.fitness.coins.wallet.repository.LedgerEntryRepository;
import io.github.returnaction.fitness.coins.wallet.repository.WalletRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
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
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest("3f1b0d9e-6a99-4b30-9c5e-1e2a9f1d0c77".getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.parse("2026-11-16T12:34:56Z");
        Instant next = Instant.parse("2026-11-17T12:34:56Z");
        LocalDate claimDate = LocalDate.parse("2026-11-16");

        transactions.executeWithoutResult(_ -> wallets.insertIfAbsent(userId));

        ledgerEntries.save(new LedgerEntryEntity(ledgerId, userId, claimId, 0, LedgerEntryType.DAILY_REWARD,
            10, 0, null, null, now));
        claims.save(new DailyRewardClaimEntity(claimId, userId, claimDate, now, 10, ledgerId));

        var snapshot = new DailyRewardClaimResponse(10, 10, 0, claimDate, false, next, now);
        requests.save(new DailyRewardRequestEntity(requestId, userId, digest, DailyRewardOutcome.GRANTED,
            claimId, snapshot, now));

        var stored = requests.findByUserIdAndKeyDigest(userId, digest).orElseThrow();

        assertThat(stored.getResponseSnapshot()).isEqualTo(snapshot);
        assertThat(stored.getOutcome()).isEqualTo(DailyRewardOutcome.GRANTED);
        assertThat(stored.getClaimId()).isEqualTo(claimId);

        transactions.executeWithoutResult(_ -> {
            Object[] row = (Object[]) entityManager.createNativeQuery("""
                    SELECT jsonb_typeof(response_snapshot),
                           response_snapshot ->> 'claimedAmount',
                           octet_length(key_digest)
                    FROM daily_reward_requests
                    WHERE id = :id
                    """)
                .setParameter("id", requestId)
                .getSingleResult();
            assertThat(row[0]).isEqualTo("object");
            assertThat(row[1]).isEqualTo("10");
            assertThat(((Number) row[2]).intValue()).isEqualTo(32);
        });
    }

    @Test
    void ledgerIsAppendOnly() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-16T12:34:56Z");

        transactions.executeWithoutResult(_ -> wallets.insertIfAbsent(userId));
        ledgerEntries.save(new LedgerEntryEntity(ledgerId, userId, UUID.randomUUID(), 0,
            LedgerEntryType.DAILY_REWARD, 10, 0, null, null, now));

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
