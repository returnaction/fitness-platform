package io.github.returnaction.fitness.challenge.dto;

import java.time.Instant;
import java.util.UUID;

public record ChallengeResultResponse(UUID challengeId, Status status, FinalResult result) {

    public enum Status {
        PENDING,
        FINAL,
        CANCELLED
    }

    public record FinalResult(UUID resultId, String outcome, Instant finalizedAt,
                              String viewerOutcome, long winnersCount) {
    }
}
