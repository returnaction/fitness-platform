package io.github.returnaction.fitness.coins.stake;

import java.util.UUID;

public record PutStakeBatchResult(UUID sealOperationId, int batchNumber) {
}
