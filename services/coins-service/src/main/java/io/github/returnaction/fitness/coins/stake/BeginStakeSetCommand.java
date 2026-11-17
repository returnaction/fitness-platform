package io.github.returnaction.fitness.coins.stake;

import java.util.Objects;
import java.util.UUID;

public record BeginStakeSetCommand(UUID sealOperationId, UUID challengeId, long setVersion,
                                   long expectedCount, String expectedDigestHex) {

    public BeginStakeSetCommand {
        Objects.requireNonNull(sealOperationId, "sealOperationId");
        Objects.requireNonNull(challengeId, "challengeId");
        if (setVersion <= 0 || expectedCount <= 0) {
            throw new IllegalArgumentException("setVersion and expectedCount must be positive");
        }
        StakeDigest.fromHex(expectedDigestHex);
    }
}
