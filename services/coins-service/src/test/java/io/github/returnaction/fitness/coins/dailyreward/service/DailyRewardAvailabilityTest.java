package io.github.returnaction.fitness.coins.dailyreward.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DailyRewardAvailabilityTest {

    @Test
    void waitsFullTwentyFourHoursEvenWhenNextUtcDayAlreadyStarted() {
        Instant claimedAt = Instant.parse("2026-11-16T23:30:00Z");

        Instant next = DailyRewardAvailability.nextClaimAt(claimedAt);

        assertThat(next).isEqualTo(Instant.parse("2026-11-17T23:30:00Z"));
    }

    @Test
    void doesNotBecomeAvailableAtMidnightAfterAnEveningClaim() {
        Instant claimedAt = Instant.parse("2026-11-16T17:00:00Z");

        Instant next = DailyRewardAvailability.nextClaimAt(claimedAt);

        assertThat(next).isEqualTo(Instant.parse("2026-11-17T17:00:00Z"));
        assertThat(DailyRewardAvailability.isAvailable(Instant.parse("2026-11-17T00:00:00Z"), next))
            .isFalse();
    }

    @Test
    void becomesAvailableExactlyAtNextClaimTime() {
        Instant next = Instant.parse("2026-11-17T00:00:00Z");

        assertThat(DailyRewardAvailability.isAvailable(next.minusNanos(1), next)).isFalse();
        assertThat(DailyRewardAvailability.isAvailable(next, next)).isTrue();
        assertThat(DailyRewardAvailability.isAvailable(next.plusNanos(1), next)).isTrue();
    }
}
