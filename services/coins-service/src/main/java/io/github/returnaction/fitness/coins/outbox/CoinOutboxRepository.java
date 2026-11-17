package io.github.returnaction.fitness.coins.outbox;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Outbox Coins: вставка в транзакции расчёта и claim publisher.
 */
@Repository
@RequiredArgsConstructor
public class CoinOutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public void insert(UUID eventId, String eventType, int schemaVersion, UUID aggregateId, String payload) {
        jdbc.update("""
                INSERT INTO coin_outbox
                    (event_id, event_type, schema_version, aggregate_id, payload)
                VALUES (:eventId, :eventType, :schemaVersion, :aggregateId, CAST(:payload AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("eventType", eventType)
                .addValue("schemaVersion", schemaVersion)
                .addValue("aggregateId", aggregateId)
                .addValue("payload", payload));
    }

    public List<OutboxRow> claim(int limit, int leaseSeconds) {
        return jdbc.query("""
                UPDATE coin_outbox
                SET lease_until = CURRENT_TIMESTAMP
                        + make_interval(secs => CAST(:leaseSeconds AS double precision)),
                    lease_generation = lease_generation + 1
                WHERE event_id IN (
                    SELECT event_id FROM coin_outbox
                    WHERE published_at IS NULL
                      AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)
                    ORDER BY created_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING event_id, event_type, schema_version, aggregate_id, payload::text AS payload
                """, new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("leaseSeconds", leaseSeconds),
                (resultSet, rowNumber) -> new OutboxRow(
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getString("event_type"),
                        resultSet.getInt("schema_version"),
                        resultSet.getObject("aggregate_id", UUID.class),
                        resultSet.getString("payload")));
    }

    public void markPublished(UUID eventId) {
        jdbc.update("""
                UPDATE coin_outbox
                SET published_at = CURRENT_TIMESTAMP, lease_until = NULL
                WHERE event_id = :eventId
                """, new MapSqlParameterSource("eventId", eventId));
    }

    public void clearLease(UUID eventId) {
        jdbc.update("UPDATE coin_outbox SET lease_until = NULL WHERE event_id = :eventId",
                new MapSqlParameterSource("eventId", eventId));
    }

    public long countUnpublished() {
        return Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT count(*) FROM coin_outbox WHERE published_at IS NULL",
                        new MapSqlParameterSource(), Long.class),
                "count query must return a value");
    }

    public record OutboxRow(UUID eventId, String eventType, int schemaVersion, UUID aggregateId,
                            String payload) {
    }
}
