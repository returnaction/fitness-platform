package io.github.returnaction.fitness.challenge.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Набор ставок Challenge и его staging-строки.
 */
@Repository
@RequiredArgsConstructor
public class ChallengeSealRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<StakeSetRow> findByChallengeId(UUID challengeId) {
        return jdbc.query("""
                SELECT challenge_id, state, version, seal_operation_id,
                       encode(digest, 'hex') AS digest_hex
                FROM challenge_stake_sets
                WHERE challenge_id = :challengeId
                """, new MapSqlParameterSource("challengeId", challengeId),
                (resultSet, rowNumber) -> new StakeSetRow(
                        resultSet.getObject("challenge_id", UUID.class),
                        resultSet.getString("state"),
                        resultSet.getLong("version"),
                        resultSet.getObject("seal_operation_id", UUID.class),
                        resultSet.getString("digest_hex")))
                .stream()
                .findFirst();
    }

    public int insertSet(UUID challengeId, long version, UUID sealOperationId) {
        return jdbc.update("""
                INSERT INTO challenge_stake_sets
                    (challenge_id, version, state, seal_operation_id)
                VALUES (:challengeId, :version, 'COLLECTING', :sealOperationId)
                ON CONFLICT (challenge_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("challengeId", challengeId)
                .addValue("version", version)
                .addValue("sealOperationId", sealOperationId));
    }

    public void insertItem(UUID challengeId, long version, UUID holdId, UUID userId, long amount) {
        jdbc.update("""
                INSERT INTO challenge_stake_set_items
                    (challenge_id, set_version, hold_id, user_id, amount)
                VALUES (:challengeId, :version, :holdId, :userId, :amount)
                """, new MapSqlParameterSource()
                .addValue("challengeId", challengeId)
                .addValue("version", version)
                .addValue("holdId", holdId)
                .addValue("userId", userId)
                .addValue("amount", amount));
    }

    public List<SealItem> findItems(UUID challengeId, long version) {
        return jdbc.query("""
                SELECT hold_id, user_id, amount
                FROM challenge_stake_set_items
                WHERE challenge_id = :challengeId AND set_version = :version
                """, new MapSqlParameterSource()
                        .addValue("challengeId", challengeId)
                        .addValue("version", version),
                (resultSet, rowNumber) -> new SealItem(
                        resultSet.getObject("hold_id", UUID.class),
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getLong("amount")));
    }

    public void markSealed(UUID challengeId, byte[] digest) {
        jdbc.update("""
                UPDATE challenge_stake_sets
                SET state = 'SEALED', digest = :digest, updated_at = CURRENT_TIMESTAMP
                WHERE challenge_id = :challengeId
                """, new MapSqlParameterSource()
                .addValue("challengeId", challengeId)
                .addValue("digest", digest));
    }

    public List<UUID> claimCollecting(int limit, int leaseSeconds) {
        return jdbc.query("""
                UPDATE challenge_stake_sets
                SET lease_until = CURRENT_TIMESTAMP
                        + make_interval(secs => CAST(:leaseSeconds AS double precision)),
                    lease_generation = lease_generation + 1
                WHERE challenge_id IN (
                    SELECT challenge_id FROM challenge_stake_sets
                    WHERE state = 'COLLECTING'
                      AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)
                    ORDER BY challenge_id
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING challenge_id
                """, new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("leaseSeconds", leaseSeconds),
                (resultSet, rowNumber) -> resultSet.getObject("challenge_id", UUID.class));
    }

    public void clearLease(UUID challengeId) {
        jdbc.update("UPDATE challenge_stake_sets SET lease_until = NULL WHERE challenge_id = :challengeId",
                new MapSqlParameterSource("challengeId", challengeId));
    }

    public record StakeSetRow(UUID challengeId, String state, long version, UUID sealOperationId,
                              String digestHex) {
    }

    public record SealItem(UUID holdId, UUID userId, long amount) {
    }
}
