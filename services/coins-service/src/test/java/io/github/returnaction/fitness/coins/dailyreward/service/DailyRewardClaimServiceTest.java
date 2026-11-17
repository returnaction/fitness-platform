package io.github.returnaction.fitness.coins.dailyreward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.returnaction.fitness.coins.dailyreward.dto.DailyRewardClaimResponse;
import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardRequestEntity;
import io.github.returnaction.fitness.coins.dailyreward.repository.DailyRewardRequestRepository;
import io.github.returnaction.fitness.coins.wallet.exception.CoinsUnavailableException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class DailyRewardClaimServiceTest {

    @Mock
    private DailyRewardRequestRepository requests;
    @Mock
    private DailyRewardClaimTransactionService transactionService;

    private DailyRewardClaimService service;

    @BeforeEach
    void setUp() {
        service = new DailyRewardClaimService(requests, transactionService);
    }

    @Test
    void rejectsNullKeyBeforeReadingDatabase() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service.claim(userId, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("idempotencyKey");

        verifyNoInteractions(requests, transactionService);
    }

    @Test
    void mapsPreCheckConnectionFailureToUnavailable() {
        UUID userId = UUID.randomUUID();
        var failure = new DataAccessResourceFailureException("private diagnostics");
        when(requests.findByUserIdAndIdempotencyKey(eq(userId), any(UUID.class)))
            .thenThrow(failure);

        assertThatThrownBy(() -> service.claim(userId, UUID.randomUUID()))
            .isInstanceOf(CoinsUnavailableException.class)
            .hasCause(failure);

        verifyNoInteractions(transactionService);
    }

    @Test
    void returnsStoredSnapshotWithoutOpeningWriteTransaction() {
        UUID userId = UUID.randomUUID();
        var request = mock(DailyRewardRequestEntity.class);
        var snapshot = new DailyRewardClaimResponse(10, 10, 0, true, Instant.parse("2026-11-17T12:34:56Z"), Instant.parse("2026-11-16T12:34:56Z"));
        when(requests.findByUserIdAndIdempotencyKey(eq(userId), any(UUID.class)))
            .thenReturn(Optional.of(request));
        when(request.getResponseSnapshot()).thenReturn(snapshot);

        assertThat(service.claim(userId, UUID.randomUUID())).isSameAs(snapshot);

        verifyNoInteractions(transactionService);
    }

    @Test
    void delegatesFirstIntentToTransactionalService() {
        UUID userId = UUID.randomUUID();
        var snapshot = new DailyRewardClaimResponse(10, 10, 0, true, Instant.parse("2026-11-17T12:34:56Z"), Instant.parse("2026-11-16T12:34:56Z"));
        when(requests.findByUserIdAndIdempotencyKey(eq(userId), any(UUID.class)))
            .thenReturn(Optional.empty());
        when(transactionService.claimDailyReward(eq(userId), any(UUID.class))).thenReturn(snapshot);

        assertThat(service.claim(userId, UUID.randomUUID())).isSameAs(snapshot);

        verify(transactionService).claimDailyReward(eq(userId), any(UUID.class));
    }
}
