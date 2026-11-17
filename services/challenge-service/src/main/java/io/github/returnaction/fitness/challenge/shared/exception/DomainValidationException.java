package io.github.returnaction.fitness.challenge.shared.exception;

import lombok.Getter;

import java.util.List;

/**
 * Нарушены правила полей или состояния; HTTP-ответ 422 validation_error.
 */
@Getter
public final class DomainValidationException extends RuntimeException {

    private final List<Detail> details;

    public DomainValidationException(List<Detail> details) {
        super("Check the submitted data.");
        this.details = List.copyOf(details);
    }

    public record Detail(String field, String code) {
    }
}
