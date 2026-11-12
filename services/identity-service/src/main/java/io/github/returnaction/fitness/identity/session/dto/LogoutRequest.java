package io.github.returnaction.fitness.identity.session.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LogoutRequest(@NotBlank @Size(max = 256) String refreshToken) {

    @Override
    public String toString() {
        return "LogoutRequest[refreshToken=<redacted>]";
    }
}
