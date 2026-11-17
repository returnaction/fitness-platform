package io.github.returnaction.fitness.coins.dailyreward.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.returnaction.fitness.coins.dailyreward.config.DailyRewardProperties;
import io.github.returnaction.fitness.coins.dailyreward.dto.DailyRewardClaimResponse;
import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardClaimEntity;
import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardOutcome;
import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardRequestEntity;
import io.github.returnaction.fitness.coins.dailyreward.repository.DailyRewardClaimRepository;
import io.github.returnaction.fitness.coins.dailyreward.repository.DailyRewardRequestRepository;
import io.github.returnaction.fitness.coins.shared.persistence.ConnectionFailures;
import io.github.returnaction.fitness.coins.shared.persistence.LockFailures;
import io.github.returnaction.fitness.coins.wallet.entity.LedgerEntryEntity;
import io.github.returnaction.fitness.coins.wallet.entity.LedgerEntryType;
import io.github.returnaction.fitness.coins.wallet.entity.WalletEntity;
import io.github.returnaction.fitness.coins.wallet.exception.CoinsUnavailableException;
import io.github.returnaction.fitness.coins.wallet.exception.WalletInvariantViolationException;
import io.github.returnaction.fitness.coins.wallet.repository.LedgerEntryRepository;
import io.github.returnaction.fitness.coins.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Транзакционная часть ежедневной награды.
 *
 * <p>Кошелёк блокируется первым: это сериализует claim одного пользователя.
 * Claim, ledger, баланс и снимок идемпотентности фиксируются одной транзакцией.
 * Ожидание блокировки ограничено {@code lock_timeout}, чтобы не удерживать
 * соединение пула слишком долго.</p>
 */
@Service
@RequiredArgsConstructor
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring-managed repositories and clock are shared")
public class DailyRewardClaimTransactionService {

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final DailyRewardClaimRepository dailyRewardClaimRepository;
    private final DailyRewardRequestRepository dailyRewardRequestRepository;
    private final DailyRewardProperties dailyRewardProperties;
    private final Clock clock;

    @Transactional(timeout = 5)
    public DailyRewardClaimResponse claimDailyReward(UUID userId, UUID idempotencyKey) {

        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");

        try {
            // 1. Блокируем кошелёк, чтобы два запроса пользователя не начислили подарок одновременно.
            // Ждём блокировку не больше двух секунд и удерживаем её до конца транзакции.
            WalletEntity wallet = lockWallet(userId);

            // 2. Пока мы ждали, другой запрос мог уже сохранить результат с этим ключом.
            var existingRequest = dailyRewardRequestRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existingRequest.isPresent()) {
                // Возвращаем сохранённый ответ: повторно начислять монеты не нужно.
                return existingRequest.get().getResponseSnapshot();
            }

            // 3. Берём серверное время с точностью до микросекунд, как в PostgreSQL.
            Instant currentTime = clock.instant().truncatedTo(ChronoUnit.MICROS);
            // 4. Находим последний подарок и прибавляем 24 часа. Если подарков ещё не было, ждать не нужно.
            var latestClaim = dailyRewardClaimRepository.findFirstByUserIdOrderByClaimedAtDesc(userId);
            Instant nextClaimAt = latestClaim
                .map(claim -> DailyRewardAvailability.nextClaimAt(claim.getClaimedAt()))
                .orElse(null);

            // 5. Если 24 часа ещё не прошли, возвращаем текущий баланс и время следующего подарка.
            if (nextClaimAt != null && !DailyRewardAvailability.isAvailable(currentTime, nextClaimAt)) {
                DailyRewardClaimEntity lastClaim = latestClaim.orElseThrow();
                var response = DailyRewardClaimResponse.notYetAvailable(
                    wallet.getAvailableBalance(),
                    wallet.getLockedBalance(),
                    nextClaimAt,
                    currentTime
                );
                // Сохраняем и этот ответ: тот же ключ должен вернуть тот же результат даже позже.
                saveRequestResult(userId,
                    idempotencyKey,
                    DailyRewardOutcome.NOT_YET_AVAILABLE,
                    lastClaim.getId(),
                    response,
                    currentTime);

                return response;
            }

            // 6. Подарок доступен: начисляем монеты и сохраняем все связанные записи в этой же транзакции.
            return grantDailyReward(wallet, idempotencyKey, currentTime);

        } catch (PessimisticLockingFailureException | DataAccessResourceFailureException |
                 TransientDataAccessResourceException | QueryTimeoutException exception) {
            // При временной проблеме с БД или блокировкой сообщаем о недоступности; транзакция откатится.
            throw new CoinsUnavailableException(exception);
        } catch (JpaSystemException exception) {
            // Hibernate может обернуть ту же проблему. Остальные ошибки не скрываем под недоступностью.
            if (ConnectionFailures.causedByConnectionFailure(exception)
                || LockFailures.causedByLockConflict(exception)) {
                throw new CoinsUnavailableException(exception);
            }
            throw exception;
        }
    }

    private WalletEntity lockWallet(UUID userId) {
        // Ограничение ожидания действует только в текущей транзакции.
        walletRepository.configureTransactionLockTimeout();
        // Кошелёк должен уже существовать; его отсутствие считаем ошибкой состояния приложения.
        return walletRepository.findByIdForUpdate(userId).orElseThrow(WalletInvariantViolationException::new);
    }

    private DailyRewardClaimResponse grantDailyReward(WalletEntity wallet,
                                                      UUID idempotencyKey,
                                                      Instant currentTime) {
        // 6.1. Берём сумму подарка из настроек сервера и рассчитываем новый баланс для ответа.
        UUID userId = wallet.getUserId();
        long rewardAmount = dailyRewardProperties.amount();
        long resultingAvailableBalance = wallet.getAvailableBalance() + rewardAmount;

        // 6.2. Увеличиваем баланс прямо в БД. Должен обновиться ровно один кошелёк.
        int updatedRows = walletRepository.incrementAvailableBalance(userId, rewardAmount, currentTime);
        if (updatedRows != 1) {
            throw new WalletInvariantViolationException();
        }

        // JPQL UPDATE изменил БД, но не объект wallet. Его не меняем и повторно не сохраняем.
        // Новый баланс рассчитан от значения, прочитанного под блокировкой кошелька.
        UUID claimId = UUID.randomUUID();
        UUID ledgerEntryId = UUID.randomUUID();

        // 6.3. Записываем в ledger, кому и сколько начислили. ID получения связывает запись с подарком.
        ledgerEntryRepository.save(new LedgerEntryEntity(
            ledgerEntryId, userId, claimId, 0, LedgerEntryType.DAILY_REWARD_CREDIT, rewardAmount, 0,
            null, null, currentTime));
        // 6.4. Запоминаем время получения: от него будут отсчитываться следующие 24 часа.
        dailyRewardClaimRepository.save(
            new DailyRewardClaimEntity(claimId, userId, currentTime, rewardAmount, ledgerEntryId));

        // 6.5. Готовим ответ с суммой награды, новыми балансами и временем следующего подарка.
        Instant nextClaimAt = DailyRewardAvailability.nextClaimAt(currentTime);
        var response = DailyRewardClaimResponse.granted(
            rewardAmount,
            resultingAvailableBalance,
            wallet.getLockedBalance(),
            nextClaimAt,
            currentTime
        );
        // 6.6. Сохраняем ответ для повтора с тем же ключом. Баланс и все записи фиксируются вместе.
        saveRequestResult(userId, idempotencyKey, DailyRewardOutcome.GRANTED, claimId, response, currentTime);
        return response;
    }

    private void saveRequestResult(UUID userId,
                                   UUID idempotencyKey,
                                   DailyRewardOutcome outcome,
                                   UUID claimId,
                                   DailyRewardClaimResponse response,
                                   Instant currentTime) {

        // Связываем пользователя и ключ с готовым ответом, чтобы вернуть его при повторном запросе.
        dailyRewardRequestRepository.save(new DailyRewardRequestEntity(
            UUID.randomUUID(), userId, idempotencyKey, outcome, claimId, response, currentTime));
    }
}
