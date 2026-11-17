package io.github.returnaction.fitness.challenge.repository;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Захват recovery-строк с lease и FOR UPDATE SKIP LOCKED.
 */
@Repository
@RequiredArgsConstructor
public class ChallengeRecoveryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public List<OperationClaim> claimOperations(int limit, int leaseSeconds) {
        return jdbc.query("""
                UPDATE challenge_operations
                SET lease_until = CURRENT_TIMESTAMP
                        + make_interval(secs => CAST(:leaseSeconds AS double precision)),
                    lease_generation = lease_generation + 1
                WHERE id IN (
                    SELECT id FROM challenge_operations
                    WHERE status IN ('PENDING', 'RESERVED')
                      AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)
                    ORDER BY created_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, challenge_id, user_id, kind, status, hold_id
                """, new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("leaseSeconds", leaseSeconds),
                (resultSet, rowNumber) -> new OperationClaim(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("challenge_id", UUID.class),
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getString("kind"),
                        resultSet.getString("status"),
                        resultSet.getObject("hold_id", UUID.class)));
    }

    public List<IntentClaim> claimReleaseIntents(int limit, int leaseSeconds) {
        return jdbc.query("""
                UPDATE challenge_release_intents
                SET lease_until = CURRENT_TIMESTAMP
                        + make_interval(secs => CAST(:leaseSeconds AS double precision)),
                    lease_generation = lease_generation + 1
                WHERE id IN (
                    SELECT id FROM challenge_release_intents
                    WHERE status = 'PENDING'
                      AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)
                    ORDER BY created_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, challenge_id, hold_id
                """, new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("leaseSeconds", leaseSeconds),
                (resultSet, rowNumber) -> new IntentClaim(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("challenge_id", UUID.class),
                        resultSet.getObject("hold_id", UUID.class)));
    }

    public void clearOperationLease(UUID operationId) {
        jdbc.update("UPDATE challenge_operations SET lease_until = NULL WHERE id = :id",
                new MapSqlParameterSource("id", operationId));
    }

    public void clearIntentLease(UUID intentId) {
        jdbc.update("UPDATE challenge_release_intents SET lease_until = NULL WHERE id = :id",
                new MapSqlParameterSource("id", intentId));
    }

    public record OperationClaim(UUID id, UUID challengeId, UUID userId, String kind, String status,
                                 UUID holdId) {
    }

    public record IntentClaim(UUID id, UUID challengeId, UUID holdId) {
    }
}
