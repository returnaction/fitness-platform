package io.github.returnaction.fitness.challenge.settlement;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * Поля `data` события `coins.settlement_completed` — camelCase.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SettlementCompletedData(
        @JsonProperty("settlementId") UUID settlementId,
        @JsonProperty("challengeId") UUID challengeId,
        @JsonProperty("resultId") UUID resultId,
        @JsonProperty("setVersion") long setVersion,
        @JsonProperty("ruleVersion") String ruleVersion,
        @JsonProperty("completedAt") Instant completedAt) {
}
