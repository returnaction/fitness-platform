package io.github.returnaction.fitness.coins.stake;

import java.util.UUID;

public record FinalizeStakeSetResult(UUID sealOperationId, UUID challengeId, long setVersion,
                                     long stakeCount, String setDigestHex) {
}
