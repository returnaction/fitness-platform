package io.github.returnaction.fitness.challenge.settlement;

import lombok.Getter;

import java.util.UUID;

/**
 * Окончательный результат ещё не сохранён; событие нельзя считать
 * применённым, доставка должна повториться.
 */
@Getter
public final class SettlementNotReadyException extends RuntimeException {

    private final UUID resultId;

    public SettlementNotReadyException(UUID resultId) {
        super("Final result is not available yet.");
        this.resultId = resultId;
    }

}
