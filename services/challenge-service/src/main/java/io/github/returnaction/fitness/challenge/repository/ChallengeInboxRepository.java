package io.github.returnaction.fitness.challenge.repository;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ChallengeInboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<byte[]> findDigest(String consumer, UUID eventId) {
        return jdbc.query("""
                SELECT payload_digest FROM challenge_inbox
                WHERE consumer = :consumer AND event_id = :eventId
                """, new MapSqlParameterSource()
                        .addValue("consumer", consumer)
                        .addValue("eventId", eventId),
                (resultSet, rowNumber) -> resultSet.getBytes("payload_digest"))
                .stream()
                .findFirst();
    }

    public int insertIfAbsent(String consumer, UUID eventId, byte[] payloadDigest) {
        return jdbc.update("""
                INSERT INTO challenge_inbox (consumer, event_id, payload_digest)
                VALUES (:consumer, :eventId, :digest)
                ON CONFLICT (consumer, event_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("consumer", consumer)
                .addValue("eventId", eventId)
                .addValue("digest", payloadDigest));
    }
}
