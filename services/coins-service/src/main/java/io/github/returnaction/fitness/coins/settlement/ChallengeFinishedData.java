package io.github.returnaction.fitness.coins.settlement;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * Поля `data` события `challenge.finished` — camelCase.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChallengeFinishedData(
        @JsonProperty("challengeId") UUID challengeId,
        @JsonProperty("resultId") UUID resultId,
        @JsonProperty("setVersion") long setVersion,
        @JsonProperty("setDigest") String setDigest,
        @JsonProperty("ruleVersion") String ruleVersion,
        @JsonProperty("outcome") String outcome,
        @JsonProperty("winnerCount") long winnerCount,
        @JsonProperty("winnersDigest") String winnersDigest) {
}
