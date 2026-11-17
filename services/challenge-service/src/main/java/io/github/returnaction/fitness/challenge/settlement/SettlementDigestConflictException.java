package io.github.returnaction.fitness.challenge.settlement;

import java.util.UUID;

/**
 * Тот же `event_id` с другим digest обнаружен конкурентно; транзакция
 * откатывается, повтор переводит событие в карантин без применения.
 */
final class SettlementDigestConflictException extends RuntimeException {

    SettlementDigestConflictException(UUID eventId) {
        super("Settlement event payload conflicts with a concurrent delivery, event_id=" + eventId);
    }
}
