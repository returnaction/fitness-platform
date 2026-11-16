package io.github.returnaction.fitness.coins.dailyreward.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Правило доступности ежедневной награды: новый календарный день UTC
 * и не менее 24 часов с предыдущего получения.
 */
public final class DailyRewardSchedule {

    private static final Duration CLAIM_INTERVAL = Duration.ofHours(24);

    private DailyRewardSchedule() {
    }

    public static Instant nextClaimAt(LocalDate claimDate, Instant claimedAt) {
        Instant nextUtcMidnight = claimDate.plusDays(1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant();
        Instant afterInterval = claimedAt.plus(CLAIM_INTERVAL);
        return afterInterval.isAfter(nextUtcMidnight) ? afterInterval : nextUtcMidnight;
    }

    public static boolean isAvailable(Instant now, Instant nextClaimAt) {
        return !now.isBefore(nextClaimAt);
    }
}
