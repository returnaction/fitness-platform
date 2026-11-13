package io.github.returnaction.fitness.accounts.profile.exception;

import io.github.returnaction.fitness.accounts.shared.web.ApiErrorResponse;
import lombok.Getter;

import java.util.List;

@Getter
public class ProfileValidationException extends RuntimeException {

    private final List<ApiErrorResponse.ErrorDetail> details;

    public ProfileValidationException(List<ApiErrorResponse.ErrorDetail> details) {
        super("Profile validation failed.");
        this.details = List.copyOf(details);
    }
}
