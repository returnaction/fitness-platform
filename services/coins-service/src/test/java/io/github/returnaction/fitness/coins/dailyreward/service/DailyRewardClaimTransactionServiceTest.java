package io.github.returnaction.fitness.coins.dailyreward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.returnaction.fitness.coins.dailyreward.config.DailyRewardProperties;
import io.github.returnaction.fitness.coins.dailyreward.dto.DailyRewardClaimResponse;
import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardRequestEntity;
import io.github.returnaction.fitness.coins.dailyreward.repository.DailyRewardClaimRepository;
import io.github.returnaction.fitness.coins.dailyreward.repository.DailyRewardRequestRepository;
import io.github.returnaction.fitness.coins.wallet.entity.WalletEntity;
import io.github.returnaction.fitness.coins.wallet.exception.CoinsUnavailableException;
import io.github.returnaction.fitness.coins.wallet.repository.LedgerEntryRepository;
import io.github.returnaction.fitness.coins.wallet.repository.WalletRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class DailyRewardClaimTransactionServiceTest {

    @Mock
    private WalletRepository wallets;
    @Mock
    private LedgerEntryRepository ledgerEntries;
    @Mock
    private DailyRewardClaimRepository claims;
    @Mock
    private DailyRewardRequestRepository requests;
    @Mock
    private DailyRewardProperties properties;
    @Mock
    private Clock clock;

    private DailyRewardClaimTransactionService service;

    @BeforeEach
    void setUp() {
        service = new DailyRewardClaimTransactionService(
            wallets, ledgerEntries, claims, requests, properties, clock);
    }

    @Test
    void returnsStoredSnapshotUnderLockBeforeCheckingAvailability() {
        UUID userId = UUID.randomUUID();
        var wallet = mock(WalletEntity.class);
        var request = mock(DailyRewardRequestEntity.class);
        var snapshot = new DailyRewardClaimResponse(10, 10, 0, true, Instant.parse("2026-11-17T12:34:56Z"), Instant.parse("2026-11-16T12:34:56Z"));
        when(wallets.findByIdForUpdate(userId)).thenReturn(Optional.of(wallet));
        when(requests.findByUserIdAndIdempotencyKey(eq(userId), any(UUID.class)))
            .thenReturn(Optional.of(request));
        when(request.getResponseSnapshot()).thenReturn(snapshot);

        assertThat(service.claimDailyReward(userId, UUID.randomUUID())).isSameAs(snapshot);

        verifyNoInteractions(ledgerEntries, claims, properties, clock);
    }

    @Test
    void mapsDatabaseConnectionFailureToUnavailable() {
        UUID userId = UUID.randomUUID();
        var failure = new DataAccessResourceFailureException("private diagnostics");
        when(wallets.findByIdForUpdate(userId)).thenThrow(failure);

        assertThatThrownBy(() -> service.claimDailyReward(userId, UUID.randomUUID()))
            .isInstanceOf(CoinsUnavailableException.class)
            .hasCause(failure);
    }

    @Test
    void mapsLockTimeoutToUnavailable() {
        UUID userId = UUID.randomUUID();
        var failure = new CannotAcquireLockException("lock timeout");
        when(wallets.findByIdForUpdate(userId)).thenThrow(failure);

        assertThatThrownBy(() -> service.claimDailyReward(userId, UUID.randomUUID()))
            .isInstanceOf(CoinsUnavailableException.class)
            .hasCause(failure);
    }
}
