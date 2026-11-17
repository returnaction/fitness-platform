package io.github.returnaction.fitness.challenge.dto;

/**
 * Тело создания черновика. Контракт требует пустой JSON-объект; неизвестные
 * поля отклоняются строгим Jackson.
 */
public record CreateDraftRequest() {
}
