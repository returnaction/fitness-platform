package io.github.returnaction.fitness.coins.dailyreward.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record DailyRewardClaimResponse(long claimedAmount,
                                       long availableBalance,
                                       long lockedBalance,
                                       boolean granted,
                                       Instant nextClaimAt,
                                       Instant serverTime) {

    public static DailyRewardClaimResponse granted(long amount,
                                                   long availableBalance,
                                                   long lockedBalance,
                                                   Instant nextClaimAt,
                                                   Instant serverTime) {
        return new DailyRewardClaimResponse(
            amount,
            availableBalance,
            lockedBalance,
            true,
            nextClaimAt,
            serverTime
        );
    }

    public static DailyRewardClaimResponse notYetAvailable(long availableBalance,
                                                          long lockedBalance,
                                                          Instant nextClaimAt,
                                                          Instant serverTime) {
        return new DailyRewardClaimResponse(
            0,
            availableBalance,
            lockedBalance,
            false,
            nextClaimAt,
            serverTime
        );
    }
}
