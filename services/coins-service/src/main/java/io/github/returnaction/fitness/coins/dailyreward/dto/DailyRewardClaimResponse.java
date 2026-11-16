package io.github.returnaction.fitness.coins.dailyreward.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record DailyRewardClaimResponse(
    long claimedAmount,
    long availableBalance,
    long lockedBalance,
    LocalDate claimedForDate,
    boolean alreadyClaimed,
    Instant nextClaimAt,
    Instant serverTime
) {
}
