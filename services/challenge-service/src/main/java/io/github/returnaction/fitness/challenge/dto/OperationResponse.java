package io.github.returnaction.fitness.challenge.dto;

import io.github.returnaction.fitness.challenge.domain.ChallengeOperationKind;
import java.util.UUID;

/**
 * Состояние долговечной операции. Результат может быть null, пока операция
 * выполняется.
 */
public record OperationResponse(UUID operationId,
                                ChallengeOperationKind kind,
                                OperationStatus status,
                                UUID challengeId,
                                Object result) {

    public enum OperationStatus {
        PENDING,
        SUCCEEDED,
        FAILED
    }

    public record OperationFailure(String code) {
    }
}
