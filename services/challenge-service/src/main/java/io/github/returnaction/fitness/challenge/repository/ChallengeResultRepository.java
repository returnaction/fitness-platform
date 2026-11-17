package io.github.returnaction.fitness.challenge.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ChallengeResultRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<ResultRow> findByResultId(UUID resultId) {
        return jdbc.query("""
                SELECT id, challenge_id, set_version, outcome, rule_version, winner_count,
                       encode(winners_digest, 'hex') AS winners_digest_hex
                FROM challenge_results
                WHERE id = :resultId
                """, new MapSqlParameterSource("resultId", resultId),
                (resultSet, rowNumber) -> new ResultRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("challenge_id", UUID.class),
                        resultSet.getLong("set_version"),
                        resultSet.getString("outcome"),
                        resultSet.getString("rule_version"),
                        resultSet.getLong("winner_count"),
                        resultSet.getString("winners_digest_hex")))
                .stream()
                .findFirst();
    }

    public Optional<ResultRow> findByChallengeId(UUID challengeId) {
        return jdbc.query("""
                SELECT id, challenge_id, set_version, outcome, rule_version, winner_count,
                       encode(winners_digest, 'hex') AS winners_digest_hex
                FROM challenge_results
                WHERE challenge_id = :challengeId
                """, new MapSqlParameterSource("challengeId", challengeId),
                (resultSet, rowNumber) -> new ResultRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("challenge_id", UUID.class),
                        resultSet.getLong("set_version"),
                        resultSet.getString("outcome"),
                        resultSet.getString("rule_version"),
                        resultSet.getLong("winner_count"),
                        resultSet.getString("winners_digest_hex")))
                .stream()
                .findFirst();
    }

    public void insertResult(UUID resultId, UUID challengeId, long setVersion, String outcome,
                             String ruleVersion, long winnerCount, byte[] winnersDigest) {
        jdbc.update("""
                INSERT INTO challenge_results
                    (id, challenge_id, set_version, outcome, rule_version, winner_count, winners_digest)
                VALUES (:id, :challengeId, :setVersion, :outcome, :ruleVersion, :winnerCount, :digest)
                """, new MapSqlParameterSource()
                .addValue("id", resultId)
                .addValue("challengeId", challengeId)
                .addValue("setVersion", setVersion)
                .addValue("outcome", outcome)
                .addValue("ruleVersion", ruleVersion)
                .addValue("winnerCount", winnerCount)
                .addValue("digest", winnersDigest));
    }

    public void insertWinner(UUID resultId, UUID userId) {
        jdbc.update("INSERT INTO challenge_result_winners (result_id, user_id) VALUES (:resultId, :userId)",
                new MapSqlParameterSource()
                        .addValue("resultId", resultId)
                        .addValue("userId", userId));
    }

    public List<UUID> findWinnerIds(UUID resultId) {
        return jdbc.query("""
                SELECT user_id FROM challenge_result_winners
                WHERE result_id = :resultId ORDER BY user_id
                """, new MapSqlParameterSource("resultId", resultId),
                (resultSet, rowNumber) -> resultSet.getObject("user_id", UUID.class));
    }

    public List<UUID> findWinnerPage(UUID resultId, UUID afterUserId, int limit) {
        if (afterUserId == null) {
            return jdbc.query("""
                    SELECT user_id FROM challenge_result_winners
                    WHERE result_id = :resultId
                    ORDER BY user_id
                    LIMIT :limit
                    """, new MapSqlParameterSource()
                            .addValue("resultId", resultId)
                            .addValue("limit", limit),
                    (resultSet, rowNumber) -> resultSet.getObject("user_id", UUID.class));
        }
        return jdbc.query("""
                SELECT user_id FROM challenge_result_winners
                WHERE result_id = :resultId AND user_id > :afterUser
                ORDER BY user_id
                LIMIT :limit
                """, new MapSqlParameterSource()
                        .addValue("resultId", resultId)
                        .addValue("afterUser", afterUserId)
                        .addValue("limit", limit),
                (resultSet, rowNumber) -> resultSet.getObject("user_id", UUID.class));
    }

    public record ResultRow(UUID id, UUID challengeId, long setVersion, String outcome, String ruleVersion,
                            long winnerCount, String winnersDigestHex) {
    }
}
