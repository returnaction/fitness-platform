package io.github.returnaction.fitness.coins.settlement;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CoinInboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<byte[]> findHash(String consumer, UUID eventId) {
        return jdbc.query("""
                SELECT payload_hash FROM coin_inbox
                WHERE consumer = :consumer AND event_id = :eventId
                """, new MapSqlParameterSource()
                        .addValue("consumer", consumer)
                        .addValue("eventId", eventId),
                (resultSet, rowNumber) -> resultSet.getBytes("payload_hash"))
                .stream()
                .findFirst();
    }

    public int insertIfAbsent(String consumer, UUID eventId, byte[] payloadHash) {
        return jdbc.update("""
                INSERT INTO coin_inbox (consumer, event_id, payload_hash)
                VALUES (:consumer, :eventId, :hash)
                ON CONFLICT (consumer, event_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("consumer", consumer)
                .addValue("eventId", eventId)
                .addValue("hash", payloadHash));
    }
}
