package io.github.returnaction.fitness.challenge.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ChallengeSettlementStatusRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<SettlementRow> findByChallengeId(UUID challengeId) {
        return jdbc.query("""
                SELECT challenge_id, result_id, settlement_id
                FROM challenge_settlement_status
                WHERE challenge_id = :challengeId
                """, new MapSqlParameterSource("challengeId", challengeId),
                (resultSet, rowNumber) -> new SettlementRow(
                        resultSet.getObject("challenge_id", UUID.class),
                        resultSet.getObject("result_id", UUID.class),
                        resultSet.getObject("settlement_id", UUID.class)))
                .stream()
                .findFirst();
    }

    public int insert(UUID challengeId, UUID resultId, UUID settlementId, UUID sourceEventId,
                      Instant completedAt) {
        return jdbc.update("""
                INSERT INTO challenge_settlement_status
                    (challenge_id, result_id, settlement_id, state, completed_at, source_event_id, updated_at)
                VALUES (:challengeId, :resultId, :settlementId, 'COMPLETED', :completedAt, :sourceEventId,
                        CURRENT_TIMESTAMP)
                ON CONFLICT (challenge_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("challengeId", challengeId)
                .addValue("resultId", resultId)
                .addValue("settlementId", settlementId)
                .addValue("completedAt", OffsetDateTime.ofInstant(completedAt, ZoneOffset.UTC))
                .addValue("sourceEventId", sourceEventId));
    }

    public record SettlementRow(UUID challengeId, UUID resultId, UUID settlementId) {
    }
}
