package io.github.returnaction.fitness.identity.session.dto;

import io.github.returnaction.fitness.identity.session.validation.ValidLoginCredentials;
import jakarta.validation.constraints.NotNull;

@ValidLoginCredentials
public record LoginRequest(@NotNull(message = "required") String email,
        @NotNull(message = "required") String password) {
    @Override
    public String toString() {
        return "LoginRequest[email=<redacted>, password=<redacted>]";
    }
}
