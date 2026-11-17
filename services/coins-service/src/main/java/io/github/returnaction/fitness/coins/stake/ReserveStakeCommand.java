package io.github.returnaction.fitness.coins.stake;

import java.util.Objects;
import java.util.UUID;

public record ReserveStakeCommand(UUID joinOperationId, UUID userId, UUID challengeId, long amount) {

    public ReserveStakeCommand {
        Objects.requireNonNull(joinOperationId, "joinOperationId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(challengeId, "challengeId");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
