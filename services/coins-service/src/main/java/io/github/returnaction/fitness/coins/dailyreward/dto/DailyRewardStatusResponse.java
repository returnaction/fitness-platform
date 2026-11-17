package io.github.returnaction.fitness.coins.dailyreward.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record DailyRewardStatusResponse(boolean available,
                                        long amount,
                                        Instant nextClaimAt,
                                        Instant serverTime,
                                        long availableBalance,
                                        long lockedBalance) {
}
