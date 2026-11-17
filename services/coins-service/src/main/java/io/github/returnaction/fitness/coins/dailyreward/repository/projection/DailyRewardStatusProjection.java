package io.github.returnaction.fitness.coins.dailyreward.repository.projection;

import java.time.Instant;

/** Только данные для статуса подарка, прочитанные одним запросом к БД. */
public interface DailyRewardStatusProjection {

    long getAvailableBalance();

    long getLockedBalance();

    /** Время последнего получения или null, если пользователь ещё не получал подарок. */
    Instant getLastClaimedAt();
}
