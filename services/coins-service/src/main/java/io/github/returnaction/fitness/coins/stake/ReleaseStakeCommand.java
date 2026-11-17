package io.github.returnaction.fitness.coins.stake;

import java.util.Objects;
import java.util.UUID;

public record ReleaseStakeCommand(UUID releaseOperationId, UUID holdId, UUID challengeId,
                                  ReleaseReason reason) {

    public ReleaseStakeCommand {
        Objects.requireNonNull(releaseOperationId, "releaseOperationId");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(challengeId, "challengeId");
        Objects.requireNonNull(reason, "reason");
    }
}
