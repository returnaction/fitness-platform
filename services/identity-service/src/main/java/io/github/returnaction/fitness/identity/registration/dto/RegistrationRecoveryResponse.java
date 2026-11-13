package io.github.returnaction.fitness.identity.registration.dto;

import java.time.Instant;
import java.util.UUID;

public record RegistrationRecoveryResponse(UUID operationId,
                                           String status,
                                           long version,
                                           Instant recoveryBlockedAt,
                                           String lastErrorCode) {
}
