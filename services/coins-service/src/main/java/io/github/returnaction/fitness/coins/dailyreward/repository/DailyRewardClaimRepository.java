package io.github.returnaction.fitness.coins.dailyreward.repository;

import io.github.returnaction.fitness.coins.dailyreward.entity.DailyRewardClaimEntity;
import io.github.returnaction.fitness.coins.dailyreward.repository.projection.DailyRewardStatusProjection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyRewardClaimRepository extends JpaRepository<DailyRewardClaimEntity, UUID> {

    /**
     * Находит последнее получение подарка указанным пользователем.
     * Spring Data строит запрос по имени метода: выбирает записи по userId,
     * сортирует claimedAt от новых к старым (Desc) и возвращает только первую (First).
     * Вызывается из DailyRewardClaimTransactionService после блокировки кошелька,
     * чтобы проверить, прошли ли 24 часа с последнего подарка.
     * Если подарок пока недоступен, ID найденного получения также сохраняется в результате запроса.
     * Пустой Optional означает, что пользователь ещё ни разу не получал подарок.
     */
    Optional<DailyRewardClaimEntity> findFirstByUserIdOrderByClaimedAtDesc(UUID userId);

    /**
     * Одним запросом возвращает оба баланса и время последнего подарка для DailyRewardQueryService.
     * Так баланс и история относятся к одному снимку БД, даже если параллельно начисляется подарок.
     * LEFT JOIN добавляет к кошельку историю подарков этого пользователя по userId.
     * Если подарков ещё не было, кошелёк всё равно попадёт в ответ, а lastClaimedAt будет null.
     * Обычный JOIN в таком случае вообще не вернул бы кошелёк.
     * После соединения одному кошельку может соответствовать несколько строк — по одной на подарок.
     * GROUP BY собирает их в одну группу по кошельку и его балансам,
     * а MAX(claimedAt) выбирает самое позднее получение. В итоге возвращается одна строка.
     * Пустой Optional означает отсутствие самого кошелька.
     */
    @Query("""
        select wallet.availableBalance as availableBalance,
               wallet.lockedBalance as lockedBalance,
               max(claim.claimedAt) as lastClaimedAt
        from WalletEntity wallet
        left join DailyRewardClaimEntity claim on claim.userId = wallet.userId
        where wallet.userId = :userId
        group by wallet.userId, wallet.availableBalance, wallet.lockedBalance
        """)
    Optional<DailyRewardStatusProjection> findStatusByUserId(@Param("userId") UUID userId);

}
