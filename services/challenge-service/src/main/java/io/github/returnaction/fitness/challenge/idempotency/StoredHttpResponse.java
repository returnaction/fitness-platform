package io.github.returnaction.fitness.challenge.idempotency;

/**
 * Сохранённый HTTP-ответ для повторного возврата по Idempotency-Key.
 * Тело и заголовки хранятся в JSONB как JSON-текст.
 */
public record StoredHttpResponse(int status, String body, String headers) {
}
