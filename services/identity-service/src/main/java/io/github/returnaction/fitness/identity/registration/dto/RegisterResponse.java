package io.github.returnaction.fitness.identity.registration.dto;

public record RegisterResponse(RegisteredUser user, RegistrationTokenResponse session) {
}
