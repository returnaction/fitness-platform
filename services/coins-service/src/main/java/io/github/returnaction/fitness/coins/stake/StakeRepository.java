package io.github.returnaction.fitness.coins.stake;

import java.util.List;
import java.util.Objects;
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
    private static final String SEAL_OPERATION_ID = "sealOperationId";
    private static final String BATCH_NUMBER = "batchNumber";
    private static final String AMOUNT = "amount";
    private static final String USER_ID_COLUMN = "user_id";

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Создаёт строку набора при необходимости и блокирует её. Это сериализует
     * reserve и seal для одного challenge и совпадает с порядком блокировок Coins.
     */
    public String lockStakeSet(UUID challengeId) {
        return lockStakeSetSnapshot(challengeId).state();
    }

    /**
     * Блокирует строку набора и возвращает состояние и cancellation ID.
     */
    public StakeSetSnapshot lockStakeSetSnapshot(UUID challengeId) {
        var params = new MapSqlParameterSource(CHALLENGE_ID, challengeId);
        jdbc.update("""
                INSERT INTO coin_stake_sets (challenge_id, state)
                VALUES (:challengeId, 'OPEN')
                ON CONFLICT (challenge_id) DO NOTHING
                """, params);
        return jdbc.queryForObject("""
                SELECT state, cancellation_id
                FROM coin_stake_sets
                WHERE challenge_id = :challengeId
                FOR UPDATE
                """, params,
                (resultSet, rowNumber) -> new StakeSetSnapshot(
                        resultSet.getString("state"),
                        resultSet.getObject("cancellation_id", UUID.class)));
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

    public Optional<UploadSnapshot> findUpload(UUID sealOperationId) {
        return upload("""
                SELECT seal_operation_id, challenge_id, set_version, expected_count,
                       encode(expected_digest, 'hex') AS expected_digest, status
                FROM coin_stake_seal_uploads
                WHERE seal_operation_id = :sealOperationId
                """, SEAL_OPERATION_ID, sealOperationId);
    }

    public Optional<UploadSnapshot> lockUpload(UUID sealOperationId) {
        return upload("""
                SELECT seal_operation_id, challenge_id, set_version, expected_count,
                       encode(expected_digest, 'hex') AS expected_digest, status
                FROM coin_stake_seal_uploads
                WHERE seal_operation_id = :sealOperationId
                FOR UPDATE
                """, SEAL_OPERATION_ID, sealOperationId);
    }

    public Optional<UploadSnapshot> findActiveUpload(UUID challengeId) {
        return upload("""
                SELECT seal_operation_id, challenge_id, set_version, expected_count,
                       encode(expected_digest, 'hex') AS expected_digest, status
                FROM coin_stake_seal_uploads
                WHERE challenge_id = :challengeId AND status = 'UPLOADING'
                """, CHALLENGE_ID, challengeId);
    }

    private Optional<UploadSnapshot> upload(String sql, String parameterName, UUID parameter) {
        return jdbc.query(sql, new MapSqlParameterSource(parameterName, parameter),
                (resultSet, rowNumber) -> new UploadSnapshot(
                        resultSet.getObject("seal_operation_id", UUID.class),
                        resultSet.getObject("challenge_id", UUID.class),
                        resultSet.getLong("set_version"),
                        resultSet.getLong("expected_count"),
                        resultSet.getString("expected_digest"),
                        resultSet.getString("status")))
                .stream()
                .findFirst();
    }

    public void insertUpload(UUID sealOperationId, UUID challengeId, long setVersion, long expectedCount,
                             byte[] expectedDigest) {
        jdbc.update("""
                INSERT INTO coin_stake_seal_uploads
                    (seal_operation_id, challenge_id, set_version, expected_count, expected_digest, status)
                VALUES (:sealOperationId, :challengeId, :setVersion, :expectedCount, :expectedDigest, 'UPLOADING')
                """, new MapSqlParameterSource()
                .addValue(SEAL_OPERATION_ID, sealOperationId)
                .addValue(CHALLENGE_ID, challengeId)
                .addValue("setVersion", setVersion)
                .addValue("expectedCount", expectedCount)
                .addValue("expectedDigest", expectedDigest));
    }

    public void markUploadSealed(UUID sealOperationId) {
        jdbc.update("""
                UPDATE coin_stake_seal_uploads
                SET status = 'SEALED', updated_at = CURRENT_TIMESTAMP
                WHERE seal_operation_id = :sealOperationId
                """, new MapSqlParameterSource(SEAL_OPERATION_ID, sealOperationId));
    }

    public Optional<BatchSnapshot> findBatch(UUID sealOperationId, int batchNumber) {
        return jdbc.query("""
                SELECT batch_number, encode(payload_digest, 'hex') AS payload_digest
                FROM coin_stake_seal_batches
                WHERE seal_operation_id = :sealOperationId AND batch_number = :batchNumber
                """, new MapSqlParameterSource()
                        .addValue(SEAL_OPERATION_ID, sealOperationId)
                        .addValue(BATCH_NUMBER, batchNumber),
                (resultSet, rowNumber) -> new BatchSnapshot(
                        resultSet.getInt("batch_number"),
                        resultSet.getString("payload_digest")))
                .stream()
                .findFirst();
    }

    public void insertBatch(UUID sealOperationId, int batchNumber, byte[] payloadDigest, int itemCount) {
        jdbc.update("""
                INSERT INTO coin_stake_seal_batches
                    (seal_operation_id, batch_number, payload_digest, item_count)
                VALUES (:sealOperationId, :batchNumber, :payloadDigest, :itemCount)
                """, new MapSqlParameterSource()
                .addValue(SEAL_OPERATION_ID, sealOperationId)
                .addValue(BATCH_NUMBER, batchNumber)
                .addValue("payloadDigest", payloadDigest)
                .addValue("itemCount", itemCount));
    }

    public void insertSealItem(UUID sealOperationId, int batchNumber, SealedStake stake) {
        jdbc.update("""
                INSERT INTO coin_stake_seal_items
                    (seal_operation_id, batch_number, hold_id, user_id, amount)
                VALUES (:sealOperationId, :batchNumber, :holdId, :userId, :amount)
                """, new MapSqlParameterSource()
                .addValue(SEAL_OPERATION_ID, sealOperationId)
                .addValue(BATCH_NUMBER, batchNumber)
                .addValue(HOLD_ID, stake.holdId())
                .addValue(USER_ID, stake.userId())
                .addValue(AMOUNT, stake.amount()));
    }

    public List<SealedStake> findSealItems(UUID sealOperationId) {
        return jdbc.query("""
                SELECT hold_id, user_id, amount
                FROM coin_stake_seal_items
                WHERE seal_operation_id = :sealOperationId
                """, new MapSqlParameterSource(SEAL_OPERATION_ID, sealOperationId),
                (resultSet, rowNumber) -> new SealedStake(
                        resultSet.getObject("hold_id", UUID.class),
                        resultSet.getObject(USER_ID_COLUMN, UUID.class),
                        resultSet.getLong(AMOUNT)));
    }

    public List<SealedStake> findActiveStakes(UUID challengeId) {
        return jdbc.query("""
                SELECT id AS hold_id, user_id, amount
                FROM coin_holds
                WHERE challenge_id = :challengeId AND status = 'ACTIVE'
                """, new MapSqlParameterSource(CHALLENGE_ID, challengeId),
                (resultSet, rowNumber) -> new SealedStake(
                        resultSet.getObject("hold_id", UUID.class),
                        resultSet.getObject(USER_ID_COLUMN, UUID.class),
                        resultSet.getLong(AMOUNT)));
    }

    public int sealStakeSet(UUID challengeId, long setVersion, byte[] digest) {
        return jdbc.update("""
                UPDATE coin_stake_sets
                SET state = 'SEALED',
                    set_version = :setVersion,
                    digest = :digest,
                    updated_at = CURRENT_TIMESTAMP
                WHERE challenge_id = :challengeId AND state = 'OPEN'
                """, new MapSqlParameterSource()
                .addValue(CHALLENGE_ID, challengeId)
                .addValue("setVersion", setVersion)
                .addValue("digest", digest));
    }

    public int markStakeSetDraining(UUID challengeId, UUID cancellationId) {
        return jdbc.update("""
                UPDATE coin_stake_sets
                SET state = 'CANCEL_DRAINING',
                    cancellation_id = :cancellationId,
                    updated_at = CURRENT_TIMESTAMP
                WHERE challenge_id = :challengeId AND state IN ('OPEN', 'SEALED')
                """, new MapSqlParameterSource()
                .addValue(CHALLENGE_ID, challengeId)
                .addValue("cancellationId", cancellationId));
    }

    public int markStakeSetCancelled(UUID challengeId, UUID cancellationId) {
        return jdbc.update("""
                UPDATE coin_stake_sets
                SET state = 'CANCELLED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE challenge_id = :challengeId
                  AND state = 'CANCEL_DRAINING'
                  AND cancellation_id = :cancellationId
                """, new MapSqlParameterSource()
                .addValue(CHALLENGE_ID, challengeId)
                .addValue("cancellationId", cancellationId));
    }

    public long countActiveHolds(UUID challengeId) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*) FROM coin_holds
                WHERE challenge_id = :challengeId AND status = 'ACTIVE'
                """, new MapSqlParameterSource(CHALLENGE_ID, challengeId), Long.class),
                "count query must return a value");
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
                .addValue(AMOUNT, amount));
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
                        resultSet.getObject(USER_ID_COLUMN, UUID.class),
                        resultSet.getObject("challenge_id", UUID.class),
                        resultSet.getLong(AMOUNT),
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

    public record UploadSnapshot(UUID sealOperationId, UUID challengeId, long setVersion,
                                 long expectedCount, String expectedDigestHex, String status) {
    }

    public record BatchSnapshot(int batchNumber, String payloadDigestHex) {
    }

    public record StakeSetSnapshot(String state, UUID cancellationId) {
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
