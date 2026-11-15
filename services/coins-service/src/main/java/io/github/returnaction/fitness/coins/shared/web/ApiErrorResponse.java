package io.github.returnaction.fitness.coins.shared.web;

import java.util.List;

/**
 * Локальный формат ошибок Coins.
 *
 * <p>Spec 017 T004.8: повторяет контракт Identity/Accounts
 * ({@code code}, {@code message}, {@code details}, {@code requestId}) без
 * нового общего модуля.</p>
 */
public record ApiErrorResponse(Error error) {

    public record Error(String code,
                        String message,
                        List<ErrorDetail> details,
                        String requestId) {

        public Error {
            details = List.copyOf(details);
        }
    }

    public record ErrorDetail(String field,
                              String code) {
    }
}
