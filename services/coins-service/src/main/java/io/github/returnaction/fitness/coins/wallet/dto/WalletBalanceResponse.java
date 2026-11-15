package io.github.returnaction.fitness.coins.wallet.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Снимок баланса владельца. Оба значения — целые неотрицательные монеты.
 *
 * <p>Spec 017 T004.1: immutable DTO ответа {@code GET /api/v1/coins/me}
 * с обязательными {@code availableBalance} и {@code lockedBalance}.</p>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record WalletBalanceResponse(long availableBalance, long lockedBalance) {
}
