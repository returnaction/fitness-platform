package io.github.returnaction.fitness.coins.stake;

import java.util.Optional;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Явные SQL-операции резервирования ставки в одной транзакции Coins.
 */
@Repository
@RequiredArgsConstructor
public class StakeRepository {

    private static final String CHALLENGE_ID = "challengeId";
    private static final String USER_ID = "userId";
    private static final String HOLD_ID = "holdId";

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Создаёт строку набора при необходимости и блокирует её. Это сериализует
     * reserve для одного challenge и совпадает с порядком блокировок Coins.
     */
    public void lockStakeSet(UUID challengeId) {
        var params = new MapSqlParameterSource(CHALLENGE_ID, challengeId);
        jdbc.update("""
                INSERT INTO coin_stake_sets (challenge_id, state)
                VALUES (:challengeId, 'OPEN')
                ON CONFLICT (challenge_id) DO NOTHING
                """, params);
        jdbc.queryForObject("""
                SELECT state FROM coin_stake_sets WHERE challenge_id = :challengeId FOR UPDATE
                """, params, String.class);
    }

    public Optional<OperationSnapshot> findOperation(String kind, UUID id) {
        return jdbc.query("""
                SELECT request_hash, result::text AS result
                FROM coin_stake_operations
                WHERE kind = :kind AND id = :id
                """, new MapSqlParameterSource()
                        .addValue("kind", kind)
                        .addValue("id", id),
                (resultSet, rowNumber) -> new OperationSnapshot(
                        resultSet.getBytes("request_hash"),
                        resultSet.getString("result")))
                .stream()
                .findFirst();
    }

    public Optional<UUID> findActiveHold(UUID challengeId, UUID userId) {
        return jdbc.query("""
                SELECT id FROM coin_holds
                WHERE challenge_id = :challengeId AND user_id = :userId AND status = 'ACTIVE'
                """, new MapSqlParameterSource()
                        .addValue(CHALLENGE_ID, challengeId)
                        .addValue(USER_ID, userId),
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class))
                .stream()
                .findFirst();
    }

    public Optional<WalletBalances> lockWallet(UUID userId) {
        return jdbc.query("""
                SELECT available_balance, locked_balance
                FROM coin_wallets
                WHERE user_id = :userId
                FOR UPDATE
                """, new MapSqlParameterSource(USER_ID, userId),
                (resultSet, rowNumber) -> new WalletBalances(
                        resultSet.getLong("available_balance"),
                        resultSet.getLong("locked_balance")))
                .stream()
                .findFirst();
    }

    public void updateWallet(UUID userId, long availableBalance, long lockedBalance) {
        jdbc.update("""
                UPDATE coin_wallets
                SET available_balance = :available,
                    locked_balance = :locked,
                    updated_at = CURRENT_TIMESTAMP
                WHERE user_id = :userId
                """, new MapSqlParameterSource()
                .addValue(USER_ID, userId)
                .addValue("available", availableBalance)
                .addValue("locked", lockedBalance));
    }

    public void insertHold(UUID holdId, UUID userId, UUID challengeId, UUID joinOperationId, long amount) {
        jdbc.update("""
                INSERT INTO coin_holds
                    (id, user_id, challenge_id, join_operation_id, amount, status)
                VALUES (:id, :userId, :challengeId, :joinOperationId, :amount, 'ACTIVE')
                """, new MapSqlParameterSource()
                .addValue("id", holdId)
                .addValue(USER_ID, userId)
                .addValue(CHALLENGE_ID, challengeId)
                .addValue("joinOperationId", joinOperationId)
                .addValue("amount", amount));
    }

    public void insertReserveLedger(UUID id, UUID userId, UUID joinOperationId, UUID challengeId,
                                    long amount) {
        jdbc.update("""
                INSERT INTO coin_ledger_entries
                    (id, user_id, operation_id, entry_index, type,
                     available_delta, locked_delta, challenge_id)
                VALUES (:id, :userId, :operationId, 0, 'RESERVE',
                        :availableDelta, :lockedDelta, :challengeId)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue(USER_ID, userId)
                .addValue("operationId", joinOperationId)
                .addValue("availableDelta", -amount)
                .addValue("lockedDelta", amount)
                .addValue(CHALLENGE_ID, challengeId));
    }

    public void insertReleaseLedger(UUID id, UUID userId, UUID releaseOperationId, UUID challengeId,
                                    long amount) {
        jdbc.update("""
                INSERT INTO coin_ledger_entries
                    (id, user_id, operation_id, entry_index, type,
                     available_delta, locked_delta, challenge_id)
                VALUES (:id, :userId, :operationId, 0, 'RELEASE',
                        :availableDelta, :lockedDelta, :challengeId)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue(USER_ID, userId)
                .addValue("operationId", releaseOperationId)
                .addValue("availableDelta", amount)
                .addValue("lockedDelta", -amount)
                .addValue(CHALLENGE_ID, challengeId));
    }

    public Optional<HoldSnapshot> lockHold(UUID holdId) {
        return jdbc.query("""
                SELECT id, user_id, challenge_id, amount, status
                FROM coin_holds
                WHERE id = :holdId
                FOR UPDATE
                """, new MapSqlParameterSource(HOLD_ID, holdId),
                (resultSet, rowNumber) -> new HoldSnapshot(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getObject("challenge_id", UUID.class),
                        resultSet.getLong("amount"),
                        resultSet.getString("status")))
                .stream()
                .findFirst();
    }

    public void markHoldReleased(UUID holdId) {
        jdbc.update("""
                UPDATE coin_holds
                SET status = 'RELEASED', updated_at = CURRENT_TIMESTAMP
                WHERE id = :holdId
                """, new MapSqlParameterSource(HOLD_ID, holdId));
    }

    public void insertOperation(String kind, UUID id, UUID challengeId, byte[] requestHash,
                                String resultJson) {
        jdbc.update("""
                INSERT INTO coin_stake_operations
                    (kind, id, challenge_id, request_hash, result)
                VALUES (:kind, :id, :challengeId, :requestHash, CAST(:result AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("kind", kind)
                .addValue("id", id)
                .addValue(CHALLENGE_ID, challengeId)
                .addValue("requestHash", requestHash)
                .addValue("result", resultJson));
    }

    public record WalletBalances(long availableBalance, long lockedBalance) {
    }

    public record HoldSnapshot(UUID id, UUID userId, UUID challengeId, long amount, String status) {
    }

    public static final class OperationSnapshot {

        private final byte[] requestHash;
        @Getter
        private final String result;

        public OperationSnapshot(byte[] requestHash, String result) {
            this.requestHash = requestHash.clone();
            this.result = result;
        }

        public byte[] getRequestHash() {
            return requestHash.clone();
        }
    }
}
