package io.github.returnaction.fitness.identity.shared.web;

import java.util.List;

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
