package io.github.returnaction.fitness.coins.dailyreward.service;

import io.github.returnaction.fitness.coins.dailyreward.config.DailyRewardProperties;
import io.github.returnaction.fitness.coins.dailyreward.dto.DailyRewardStatusResponse;
import io.github.returnaction.fitness.coins.dailyreward.repository.DailyRewardClaimRepository;
import io.github.returnaction.fitness.coins.shared.persistence.ConnectionFailures;
import io.github.returnaction.fitness.coins.wallet.exception.CoinsUnavailableException;
import io.github.returnaction.fitness.coins.wallet.exception.WalletInvariantViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

/** Читает статус подарка для фронта: доступность, сумму, время ожидания и балансы. Монеты не начисляет. */
@Service
@RequiredArgsConstructor
public class DailyRewardQueryService {

    private final DailyRewardClaimRepository dailyRewardClaimRepository;
    private final DailyRewardProperties dailyRewardProperties;
    private final Clock clock;

    public DailyRewardStatusResponse getStatus(UUID userId) {

        Objects.requireNonNull(userId, "userId");

        try {
            // 1. Одним запросом читаем оба баланса и последнее получение подарка.
            // Кошелёк должен уже существовать; его отсутствие считаем ошибкой состояния приложения.
            var rewardStatus = dailyRewardClaimRepository.findStatusByUserId(userId)
                .orElseThrow(WalletInvariantViolationException::new);

            // 2. Следующий подарок доступен через 24 часа после последнего. Если подарков не было — сразу.
            Instant currentTime = clock.instant();
            Instant lastClaimedAt = rewardStatus.getLastClaimedAt();
            Instant nextClaimAt = lastClaimedAt == null ? null : DailyRewardAvailability.nextClaimAt(lastClaimedAt);
            boolean available = nextClaimAt == null || DailyRewardAvailability.isAvailable(currentTime, nextClaimAt);

            // 3. Возвращаем фронту балансы и серверное время для таймера.
            // Если подарок уже доступен, время ожидания не нужно: nextClaimAt будет null.
            return new DailyRewardStatusResponse(
                available,
                dailyRewardProperties.amount(),
                available ? null : nextClaimAt,
                currentTime,
                rewardStatus.getAvailableBalance(),
                rewardStatus.getLockedBalance()
            );

        } catch (DataAccessResourceFailureException
                 | TransientDataAccessResourceException
                 | QueryTimeoutException
                 | CannotCreateTransactionException exception) {
            // Временную проблему с БД превращаем в понятную для API ошибку недоступности.
            throw new CoinsUnavailableException(exception);
        } catch (JpaSystemException exception) {
            // Hibernate может обернуть ошибку соединения. Другие ошибки не скрываем.
            if (ConnectionFailures.causedByConnectionFailure(exception)) {
                throw new CoinsUnavailableException(exception);
            }
            throw exception;
        }
    }
}
