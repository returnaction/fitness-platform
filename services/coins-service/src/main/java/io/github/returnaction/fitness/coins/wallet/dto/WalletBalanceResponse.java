package io.github.returnaction.fitness.coins.wallet.dto;

import com.fasterxml.jackson.annotation.JsonInclude;


@JsonInclude(JsonInclude.Include.ALWAYS)
public record WalletBalanceResponse(long availableBalance, long lockedBalance) {
}
