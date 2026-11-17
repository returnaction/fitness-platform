package io.github.returnaction.fitness.coins.wallet.repository;

import io.github.returnaction.fitness.coins.wallet.entity.WalletEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Читает и изменяет кошельки в БД. Решение, можно ли начислить подарок, принимает сервис.
 * Обычный findById наследуется от JpaRepository: его используют при чтении баланса,
 * когда блокировать кошелёк для изменения не нужно.
 */
public interface WalletRepository extends JpaRepository<WalletEntity, UUID> {


    /**
     * Создаёт кошелёк с нулевыми балансами, если у пользователя его ещё нет.
     * Если кошелёк уже существует, ничего не меняет и не обнуляет монеты.
     * Вызывается из WalletCreationService при обработке gRPC-запроса на создание кошелька.
     * Работает внутри транзакции сервиса.
     *
     * @return 1, если кошелёк создан; 0, если он уже существовал
     */
    @Modifying
    @Query(value = """
        INSERT INTO coin_wallets (user_id) VALUES (:userId)
        ON CONFLICT (user_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId);

    /**
     * Настраивает БД: если блокировку не удаётся получить за две секунды, завершить ожидание ошибкой.
     * Сам кошелёк этот метод не блокирует. Настройка действует только до конца текущей транзакции.
     * Вызывается из DailyRewardClaimTransactionService перед findByIdForUpdate,
     * чтобы запрос подарка не ждал занятый кошелёк слишком долго.
     *
     * @return установленное значение таймаута; сервису этот результат не нужен
     */
    @Query(value = "SELECT set_config('lock_timeout', '2s', true)", nativeQuery = true)
    String configureTransactionLockTimeout();

    /**
     * Прибавляет amount к доступному балансу прямо в БД и обновляет время изменения кошелька.
     * Зарезервированный баланс не меняет. Сам не проверяет, доступен ли подарок.
     * Вызывается из DailyRewardClaimTransactionService после блокировки кошелька,
     * проверки ключа идемпотентности и проверки 24 часов с прошлого подарка.
     * Обновление входит в ту же транзакцию, что ledger, получение подарка и сохранение ответа.
     * Уже загруженный объект WalletEntity остаётся со старым балансом: этот запрос его не обновляет.
     *
     * @return количество обновлённых кошельков; сервис ожидает ровно 1
     */
    @Modifying(flushAutomatically = true)
    @Query("""
        update WalletEntity wallet
        set wallet.availableBalance = wallet.availableBalance + :amount,
            wallet.updatedAt = :updatedAt
        where wallet.userId = :userId
        """)
    int incrementAvailableBalance(@Param("userId") UUID userId,
                                  @Param("amount") long amount,
                                  @Param("updatedAt") Instant updatedAt); //TODO зачем, если можно сделать что бы оно само записвало когда вставляем а не когда мы передаём

    /**
     * Находит кошелёк пользователя и блокирует его строку до конца текущей транзакции.
     * Другой запрос, который хочет изменить или так же заблокировать этот кошелёк, будет ждать.
     * Обычное чтение баланса при этом не запрещено.
     * Вызывается из DailyRewardClaimTransactionService перед проверкой повторного запроса
     * и доступности подарка, чтобы два запроса пользователя не начислили награду одновременно.
     *
     * @return кошелёк или пустой Optional, если кошелёк не найден
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select wallet from WalletEntity wallet where wallet.userId = :userId")
    Optional<WalletEntity> findByIdForUpdate(@Param("userId") UUID userId);
}
