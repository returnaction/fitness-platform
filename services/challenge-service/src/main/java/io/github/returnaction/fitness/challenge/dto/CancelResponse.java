package io.github.returnaction.fitness.challenge.dto;

import io.github.returnaction.fitness.challenge.domain.ChallengeStatus;
import io.github.returnaction.fitness.challenge.domain.RefundStatus;
import java.util.UUID;

public record CancelResponse(UUID challengeId, ChallengeStatus status, RefundStatus refundStatus) {
}
