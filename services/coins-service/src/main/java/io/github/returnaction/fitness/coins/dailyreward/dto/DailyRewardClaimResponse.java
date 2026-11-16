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

    public static DailyRewardClaimResponse granted(long amount,
                                                   long availableBalance,
                                                   long lockedBalance,
                                                   LocalDate claimedForDate,
                                                   Instant nextClaimAt,
                                                   Instant serverTime) {
        return new DailyRewardClaimResponse(
            amount,
            availableBalance,
            lockedBalance,
            claimedForDate,
            false,
            nextClaimAt,
            serverTime
        );
    }

    public static DailyRewardClaimResponse alreadyClaimed(long availableBalance,
                                                          long lockedBalance,
                                                          LocalDate claimedForDate,
                                                          Instant nextClaimAt,
                                                          Instant serverTime) {
        return new DailyRewardClaimResponse(
            0,
            availableBalance,
            lockedBalance,
            claimedForDate,
            true,
            nextClaimAt,
            serverTime
        );
    }
}
