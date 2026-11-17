package io.github.returnaction.fitness.challenge.domain;

import lombok.Getter;

/**
 * Некорректный переход доменного состояния.
 *
 * <p>Сообщение не содержит пользовательских данных и пригодно для внутренней
 * диагностики; HTTP-код и тело ответа формирует обработчик ошибок.</p>
 */
@Getter
public final class InvalidStateTransitionException extends RuntimeException {

    private final String currentStatus;
    private final String targetStatus;

    public InvalidStateTransitionException(Enum<?> currentStatus, Enum<?> targetStatus) {
        super("State transition is not allowed: " + name(currentStatus) + " -> " + name(targetStatus));
        this.currentStatus = name(currentStatus);
        this.targetStatus = name(targetStatus);
    }

    private static String name(Enum<?> status) {
        return status == null ? "null" : status.name();
    }
}
