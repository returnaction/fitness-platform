package io.github.returnaction.fitness.coins.dailyreward.service;

import io.github.returnaction.fitness.coins.dailyreward.dto.DailyRewardClaimResponse;
import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardRequestEntity;
import io.github.returnaction.fitness.coins.dailyreward.repository.DailyRewardRequestRepository;
import io.github.returnaction.fitness.coins.shared.persistence.ConnectionFailures;
import io.github.returnaction.fitness.coins.wallet.exception.CoinsUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Принимает запрос на подарок и проверяет, не обрабатывали ли мы его раньше.
 *
 * <p>Ищет в daily_reward_requests запись по пользователю и ключу идемпотентности.
 * Если запись есть, возвращает сохранённый responseSnapshot: это прежний ответ
 * «подарок начислен» или «пока недоступен». Повторно монеты не начисляет.
 * Например, если ответ потерялся в сети, повтор с тем же ключом получит тот же результат.</p>
 *
 * <p>Если записи нет, передаёт запрос в DailyRewardClaimTransactionService.
 * Новый ключ ещё не означает, что подарок можно получить: тот сервис блокирует
 * кошелёк, повторно ищет ключ и проверяет, прошло ли 24 часа с прошлого получения.
 * Повторная проверка нужна, потому что другой запрос мог успеть обработаться одновременно.</p>
 *
 * <p>Этот сервис сам не меняет баланс и не блокирует кошелёк.
 * При временной недоступности БД сообщает об ошибке, а не считает запрос новым.</p>
 */
@Service
@RequiredArgsConstructor
public class DailyRewardClaimService {

    private final DailyRewardRequestRepository dailyRewardRequestRepository;
    private final DailyRewardClaimTransactionService dailyRewardClaimTransactionService;

    public DailyRewardClaimResponse claim(UUID userId, UUID idempotencyKey) {

        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");

        try {
            // Шаг 1. Ищем сохранённый результат запроса по пользователю и ключу идемпотентности.
            Optional<DailyRewardRequestEntity> existingRequest =
                dailyRewardRequestRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existingRequest.isPresent()) {
                // Шаг 2. Запрос уже обработан: возвращаем прежний ответ без повторного начисления.
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

        // Шаг 3. Результат не найден: передаём запрос на проверку и возможное начисление.
        // Внутри транзакции сервис повторно проверит ключ под блокировкой кошелька и интервал 24 часа.
        return dailyRewardClaimTransactionService.claimDailyReward(userId, idempotencyKey);
    }
}
