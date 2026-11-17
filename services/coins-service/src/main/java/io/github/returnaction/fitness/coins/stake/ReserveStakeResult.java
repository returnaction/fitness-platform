package io.github.returnaction.fitness.coins.stake;

import java.util.UUID;

public record ReserveStakeResult(UUID joinOperationId, ReserveOutcome outcome, UUID holdId) {
}
