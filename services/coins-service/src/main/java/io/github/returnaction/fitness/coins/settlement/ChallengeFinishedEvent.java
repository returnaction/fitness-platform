package io.github.returnaction.fitness.coins.settlement;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * Оболочка `challenge.finished` v1: snake_case, обязательные поля.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChallengeFinishedEvent(
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("occurred_at") Instant occurredAt,
        @JsonProperty("producer") String producer,
        @JsonProperty("correlation_id") UUID correlationId,
        @JsonProperty("data") ChallengeFinishedData data) {
}
