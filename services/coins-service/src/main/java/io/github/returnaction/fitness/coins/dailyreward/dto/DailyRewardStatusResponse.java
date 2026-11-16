package io.github.returnaction.fitness.coins.dailyreward.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record DailyRewardStatusResponse(
    boolean available,
    long amount,
    LocalDate claimedForDate,
    Instant nextClaimAt,
    Instant serverTime,
    long availableBalance,
    long lockedBalance
) {
}
