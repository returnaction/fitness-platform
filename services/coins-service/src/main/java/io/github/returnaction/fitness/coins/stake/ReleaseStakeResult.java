package io.github.returnaction.fitness.coins.stake;

import java.util.UUID;

public record ReleaseStakeResult(UUID releaseOperationId, UUID holdId, ReleaseOutcome outcome) {
}
