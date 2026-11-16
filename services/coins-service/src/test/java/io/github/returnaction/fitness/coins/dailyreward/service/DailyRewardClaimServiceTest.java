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
import io.github.returnaction.fitness.coins.dailyreward.exception.InvalidIdempotencyKeyException;
import io.github.returnaction.fitness.coins.dailyreward.repository.DailyRewardRequestRepository;
import io.github.returnaction.fitness.coins.wallet.exception.CoinsUnavailableException;
import java.time.Instant;
import java.time.LocalDate;
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
    void rejectsMissingOrMalformedKeyBeforeReadingDatabase() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service.claim(userId, null))
            .isInstanceOf(InvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> service.claim(userId, "not-a-uuid"))
            .isInstanceOf(InvalidIdempotencyKeyException.class);

        verifyNoInteractions(requests, transactionService);
    }

    @Test
    void mapsPreCheckConnectionFailureToUnavailable() {
        UUID userId = UUID.randomUUID();
        var failure = new DataAccessResourceFailureException("private diagnostics");
        when(requests.findByUserIdAndKeyDigest(eq(userId), any(byte[].class)))
            .thenThrow(failure);

        assertThatThrownBy(() -> service.claim(userId, UUID.randomUUID().toString()))
            .isInstanceOf(CoinsUnavailableException.class)
            .hasCause(failure);

        verifyNoInteractions(transactionService);
    }

    @Test
    void returnsStoredSnapshotWithoutOpeningWriteTransaction() {
        UUID userId = UUID.randomUUID();
        var request = mock(DailyRewardRequestEntity.class);
        var snapshot = new DailyRewardClaimResponse(10, 10, 0, LocalDate.parse("2026-11-16"),
            false, Instant.parse("2026-11-17T12:34:56Z"), Instant.parse("2026-11-16T12:34:56Z"));
        when(requests.findByUserIdAndKeyDigest(eq(userId), any(byte[].class)))
            .thenReturn(Optional.of(request));
        when(request.getResponseSnapshot()).thenReturn(snapshot);

        assertThat(service.claim(userId, UUID.randomUUID().toString())).isSameAs(snapshot);

        verifyNoInteractions(transactionService);
    }

    @Test
    void delegatesFirstIntentToTransactionalService() {
        UUID userId = UUID.randomUUID();
        var snapshot = new DailyRewardClaimResponse(10, 10, 0, LocalDate.parse("2026-11-16"),
            false, Instant.parse("2026-11-17T12:34:56Z"), Instant.parse("2026-11-16T12:34:56Z"));
        when(requests.findByUserIdAndKeyDigest(eq(userId), any(byte[].class)))
            .thenReturn(Optional.empty());
        when(transactionService.claim(eq(userId), any(byte[].class))).thenReturn(snapshot);

        assertThat(service.claim(userId, UUID.randomUUID().toString())).isSameAs(snapshot);

        verify(transactionService).claim(eq(userId), any(byte[].class));
    }
}
