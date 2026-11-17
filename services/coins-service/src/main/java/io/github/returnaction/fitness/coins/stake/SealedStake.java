package io.github.returnaction.fitness.coins.stake;

import java.util.Objects;
import java.util.UUID;

public record SealedStake(UUID holdId, UUID userId, long amount) {

    public SealedStake {
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(userId, "userId");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
