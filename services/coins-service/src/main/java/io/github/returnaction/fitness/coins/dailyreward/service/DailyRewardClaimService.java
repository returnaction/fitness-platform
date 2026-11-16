package io.github.returnaction.fitness.coins.dailyreward.service;

import io.github.returnaction.fitness.coins.dailyreward.dto.DailyRewardClaimResponse;
import io.github.returnaction.fitness.coins.dailyreward.repository.DailyRewardRequestRepository;
import io.github.returnaction.fitness.coins.shared.persistence.ConnectionFailures;
import io.github.returnaction.fitness.coins.wallet.exception.CoinsUnavailableException;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Проверяет повтор ключа без блокировки кошелька.
 *
 * <p>Повторная доставка после таймаута — частый путь; для неё не нужно
 * открывать write-транзакцию и удерживать соединение на lock. Если снимка
 * ещё нет, вызов передаётся в транзакционный сервис, который повторно
 * проверяет ключ уже под блокировкой кошелька.</p>
 */
@Service
@RequiredArgsConstructor
public class DailyRewardClaimService {

    private final DailyRewardRequestRepository requests;
    private final DailyRewardClaimTransactionService transactionService;

    public DailyRewardClaimResponse claim(UUID userId, String rawIdempotencyKey) {

        Objects.requireNonNull(userId, "userId");
        byte[] keyDigest = DailyRewardIdempotencyKey.digest(rawIdempotencyKey);

        try {
            var existingRequest = requests.findByUserIdAndKeyDigest(userId, keyDigest);
            if (existingRequest.isPresent()) {
                return existingRequest.get().getResponseSnapshot();
            }
        } catch (DataAccessResourceFailureException
                 | TransientDataAccessResourceException
                 | QueryTimeoutException
                 | CannotCreateTransactionException exception) {
            throw new CoinsUnavailableException(exception);
        } catch (JpaSystemException exception) {
            if (ConnectionFailures.causedByConnectionFailure(exception)) {
                throw new CoinsUnavailableException(exception);
            }
            throw exception;
        }

        return transactionService.claim(userId, keyDigest);
    }
}
