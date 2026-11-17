package io.github.returnaction.fitness.coins.dailyreward.service;

import java.time.Duration;
import java.time.Instant;

/**
 * Вычисляет доступность подарка через 24 часа после получения.
 * Ничего не запускает по расписанию и не начисляет монеты.
 */
public final class DailyRewardAvailability {

    private static final Duration CLAIM_INTERVAL = Duration.ofHours(24);

    private DailyRewardAvailability() {
    }

    public static Instant nextClaimAt(Instant claimedAt) {
        return claimedAt.plus(CLAIM_INTERVAL);
    }

    public static boolean isAvailable(Instant now, Instant nextClaimAt) {
        return !now.isBefore(nextClaimAt);
    }
}
