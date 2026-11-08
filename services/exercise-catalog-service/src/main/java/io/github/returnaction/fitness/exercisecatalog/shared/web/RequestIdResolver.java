package io.github.returnaction.fitness.exercisecatalog.shared.web;

import java.util.UUID;

/**
 * Определяет идентификатор запроса из заголовка X-Request-Id.
 *
 * Возможны три ситуации:
 *
 * 1. Клиент прислал корректный UUID: 550e8400-e29b-41d4-a716-446655440000
 * Используем этот UUID.
 *
 * 2. Клиент не прислал заголовок или прислал пустое значение. Создаём новый
 * случайный UUID.
 *
 * 3. Клиент прислал некорректное значение, например "hello". Не завершаем
 * запрос с ошибкой: создаём новый случайный UUID.
 *
 * RequestIdFilter использует этот класс, чтобы получить requestId и добавить
 * его к запросу, ответу и контексту логирования.
 */
public final class RequestIdResolver {

    private RequestIdResolver() {
    }

    public static UUID resolve(String requestIdHeader) {
        if (requestIdHeader == null || requestIdHeader.isBlank()) {
            return UUID.randomUUID();
        }

        try {
            return UUID.fromString(requestIdHeader);
        } catch (IllegalArgumentException exception) {
            return UUID.randomUUID();
        }
    }
}
