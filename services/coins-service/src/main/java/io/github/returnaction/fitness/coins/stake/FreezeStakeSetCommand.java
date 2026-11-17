package io.github.returnaction.fitness.coins.stake;

import java.util.Objects;
import java.util.UUID;

public record FreezeStakeSetCommand(UUID cancellationId, UUID challengeId) {

    public FreezeStakeSetCommand {
        Objects.requireNonNull(cancellationId, "cancellationId");
        Objects.requireNonNull(challengeId, "challengeId");
    }
}
