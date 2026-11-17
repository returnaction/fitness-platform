package io.github.returnaction.fitness.challenge.service;

import java.util.UUID;

public record FinalResult(UUID resultId, UUID challengeId, long setVersion, String outcome,
                          long winnerCount, String winnersDigestHex) {
}
