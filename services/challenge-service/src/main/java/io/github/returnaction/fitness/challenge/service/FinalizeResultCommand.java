package io.github.returnaction.fitness.challenge.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record FinalizeResultCommand(UUID resultId, UUID challengeId, long setVersion, String setDigest,
                                    String outcome, String ruleVersion, List<UUID> winnerUserIds) {

    public FinalizeResultCommand {
        Objects.requireNonNull(resultId, "resultId");
        Objects.requireNonNull(challengeId, "challengeId");
        Objects.requireNonNull(setDigest, "setDigest");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(ruleVersion, "ruleVersion");
        Objects.requireNonNull(winnerUserIds, "winnerUserIds");
        winnerUserIds = List.copyOf(winnerUserIds);
        if (setVersion <= 0 || ruleVersion.isBlank() || ruleVersion.length() > 64
                || !setDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid result payload");
        }
    }
}
