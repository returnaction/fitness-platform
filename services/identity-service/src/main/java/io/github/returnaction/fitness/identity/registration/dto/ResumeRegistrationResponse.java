package io.github.returnaction.fitness.identity.registration.dto;

import java.util.UUID;

public record ResumeRegistrationResponse(UUID operationId, String status, long version) {
}
