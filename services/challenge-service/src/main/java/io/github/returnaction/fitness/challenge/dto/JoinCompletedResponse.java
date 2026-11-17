package io.github.returnaction.fitness.challenge.dto;

import io.github.returnaction.fitness.challenge.domain.ParticipationStatus;
import java.util.UUID;

public record JoinCompletedResponse(UUID challengeId, ParticipationStatus participationStatus) {
}
