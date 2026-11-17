package io.github.returnaction.fitness.challenge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.returnaction.fitness.challenge.domain.ChallengeMode;
import io.github.returnaction.fitness.challenge.domain.ChallengeStatus;
import io.github.returnaction.fitness.challenge.domain.ChallengeVisibility;
import io.github.returnaction.fitness.challenge.domain.ParticipationStatus;
import io.github.returnaction.fitness.challenge.domain.RefundStatus;
import io.github.returnaction.fitness.challenge.domain.SettlementStatus;
import io.github.returnaction.fitness.challenge.domain.StartMode;
import java.time.Instant;
import java.util.UUID;

public record ChallengeDetailsResponse(UUID id,
                                       String title,
                                       String description,
                                       ChallengeStatus status,
                                       ChallengeMode mode,
                                       ChallengeVisibility visibility,
                                       Long stakeAmount,
                                       Participants participants,
                                       Start start,
                                       Integer durationDays,
                                       Viewer viewer,
                                       RefundStatus refundStatus,
                                       SettlementStatus settlementStatus,
                                       long version,
                                       Instant createdAt,
                                       Instant updatedAt) {

    public record Participants(int min, int max, long joined) {
    }

    public record Start(StartMode mode, Instant scheduledAt, Instant startedAt) {
    }

    public record Viewer(@JsonProperty("isParticipant") boolean participant,
                         @JsonProperty("isCreator") boolean creator,
                         ParticipationStatus participationStatus) {
    }
}
