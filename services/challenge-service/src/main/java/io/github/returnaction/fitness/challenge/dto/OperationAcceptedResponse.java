package io.github.returnaction.fitness.challenge.dto;

import java.util.UUID;

public record OperationAcceptedResponse(UUID operationId, String status, String statusUrl) {
}
