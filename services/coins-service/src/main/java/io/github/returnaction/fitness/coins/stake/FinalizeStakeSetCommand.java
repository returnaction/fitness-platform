package io.github.returnaction.fitness.coins.stake;

import java.util.Objects;
import java.util.UUID;

public record FinalizeStakeSetCommand(UUID sealOperationId) {

    public FinalizeStakeSetCommand {
        Objects.requireNonNull(sealOperationId, "sealOperationId");
    }
}
