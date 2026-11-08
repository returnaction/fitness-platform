package io.github.returnaction.fitness.identity.registration.dto;

import java.util.UUID;

public record RegisteredUser(UUID id, String email, String status) {
}
