package io.github.returnaction.fitness.challenge.repository;

import io.github.returnaction.fitness.challenge.domain.SettlementStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Чтение проекции расчёта Coins для ответа GET challenge.
 */
@Repository
@RequiredArgsConstructor
public class ChallengeReadRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SettlementStatus settlementStatus(UUID challengeId) {
        String state = jdbc.query("""
                SELECT settlement.state
                FROM challenge_results result
                LEFT JOIN challenge_settlement_status settlement
                       ON settlement.challenge_id = result.challenge_id
                WHERE result.challenge_id = :challengeId
                """, Map.of("challengeId", challengeId),
                resultSet -> resultSet.next() ? resultSet.getString("state") : null);

        if (state == null) {
            return SettlementStatus.UNKNOWN;
        }
        return SettlementStatus.COMPLETED.name().equals(state)
                ? SettlementStatus.COMPLETED
                : SettlementStatus.PENDING;
    }

    public Optional<ResultProjection> findResult(UUID challengeId, UUID viewerId) {
        return jdbc.query("""
                SELECT result.id, result.outcome, result.created_at, result.winner_count,
                       participant.user_id IS NOT NULL AS viewer_participant,
                       winner.user_id IS NOT NULL AS viewer_winner
                FROM challenge_results result
                LEFT JOIN challenge_participants participant
                       ON participant.challenge_id = result.challenge_id
                      AND participant.user_id = :viewerId
                LEFT JOIN challenge_result_winners winner
                       ON winner.result_id = result.id
                      AND winner.user_id = :viewerId
                WHERE result.challenge_id = :challengeId
                """, Map.of("challengeId", challengeId, "viewerId", viewerId),
                (resultSet, _) -> new ResultProjection(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("outcome"),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                        resultSet.getLong("winner_count"),
                        resultSet.getBoolean("viewer_participant"),
                        resultSet.getBoolean("viewer_winner")))
                .stream()
                .findFirst();
    }

    public record ResultProjection(UUID resultId, String outcome, Instant finalizedAt, long winnersCount,
                                   boolean viewerParticipant, boolean viewerWinner) {
    }
}
