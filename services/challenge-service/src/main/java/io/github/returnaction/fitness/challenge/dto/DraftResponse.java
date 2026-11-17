package io.github.returnaction.fitness.challenge.dto;

import io.github.returnaction.fitness.challenge.domain.ChallengeStatus;
import java.util.UUID;

public record DraftResponse(UUID id, ChallengeStatus status, long version) {
}
