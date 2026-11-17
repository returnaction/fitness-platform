package io.github.returnaction.fitness.coins.settlement;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CoinOutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public void insert(UUID eventId, String eventType, int schemaVersion, UUID aggregateId, String payload) {
        jdbc.update("""
                INSERT INTO coin_outbox (event_id, event_type, schema_version, aggregate_id, payload)
                VALUES (:eventId, :eventType, :schemaVersion, :aggregateId, CAST(:payload AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("eventType", eventType)
                .addValue("schemaVersion", schemaVersion)
                .addValue("aggregateId", aggregateId)
                .addValue("payload", payload));
    }
}
