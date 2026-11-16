package io.github.returnaction.fitness.coins.dailyreward.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DailyRewardScheduleTest {

    @Test
    void waitsFullTwentyFourHoursEvenWhenNextUtcDayAlreadyStarted() {
        Instant claimedAt = Instant.parse("2026-11-16T23:30:00Z");

        Instant next = DailyRewardSchedule.nextClaimAt(LocalDate.parse("2026-11-16"), claimedAt);

        assertThat(next).isEqualTo(Instant.parse("2026-11-17T23:30:00Z"));
    }

    @Test
    void usesNextUtcMidnightWhenItIsLaterThanInterval() {
        Instant claimedAt = Instant.parse("2026-11-16T00:00:00Z");

        Instant next = DailyRewardSchedule.nextClaimAt(LocalDate.parse("2026-11-16"), claimedAt);

        assertThat(next).isEqualTo(Instant.parse("2026-11-17T00:00:00Z"));
    }

    @Test
    void becomesAvailableExactlyAtNextClaimTime() {
        Instant next = Instant.parse("2026-11-17T00:00:00Z");

        assertThat(DailyRewardSchedule.isAvailable(next.minusNanos(1), next)).isFalse();
        assertThat(DailyRewardSchedule.isAvailable(next, next)).isTrue();
        assertThat(DailyRewardSchedule.isAvailable(next.plusNanos(1), next)).isTrue();
    }
}
