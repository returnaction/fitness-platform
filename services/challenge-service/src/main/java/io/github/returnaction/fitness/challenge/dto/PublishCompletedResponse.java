package io.github.returnaction.fitness.challenge.dto;

import io.github.returnaction.fitness.challenge.domain.ChallengeStatus;
import io.github.returnaction.fitness.challenge.domain.ParticipationStatus;
import java.util.UUID;

public record PublishCompletedResponse(UUID id, ChallengeStatus status,
                                       ParticipationStatus participationStatus) {
}
