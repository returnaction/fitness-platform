package io.github.returnaction.fitness.identity.session.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(@NotBlank @Size(max = 256) String refreshToken) {
    @Override
    public String toString() {
        return "RefreshRequest[refreshToken=<redacted>]";
    }
}
