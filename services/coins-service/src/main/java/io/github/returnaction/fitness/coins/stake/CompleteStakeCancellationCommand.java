package io.github.returnaction.fitness.coins.stake;

import java.util.Objects;
import java.util.UUID;

public record CompleteStakeCancellationCommand(UUID cancellationId, UUID challengeId) {

    public CompleteStakeCancellationCommand {
        Objects.requireNonNull(cancellationId, "cancellationId");
        Objects.requireNonNull(challengeId, "challengeId");
    }
}
