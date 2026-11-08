package io.github.returnaction.fitness.identity.registration.model;

import java.util.UUID;

public record RegistrationStartResult(UUID operationId, UUID userId, String emailCanonical) {
}
