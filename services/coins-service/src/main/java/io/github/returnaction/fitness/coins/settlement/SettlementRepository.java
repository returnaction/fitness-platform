package io.github.returnaction.fitness.coins.settlement;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Явные SQL-операции расчёта. Порядок блокировок: stake set → settlement →
 * holds по возрастанию UUID → wallets по возрастанию user UUID.
 */
@Repository
@RequiredArgsConstructor
public class SettlementRepository {

    private static final String CHALLENGE_ID = "challengeId";
    private static final String RESULT_ID = "resultId";
    private static final String USER_ID = "userId";
    private static final String AMOUNT = "amount";
    private static final String HOLD_ID = "holdId";
    private static final String SETTLEMENT_ID = "settlementId";

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<StakeSetSnapshot> lockStakeSet(UUID challengeId) {
        return jdbc.query("""
                SELECT state, set_version, encode(digest, 'hex') AS digest_hex
                FROM coin_stake_sets
                WHERE challenge_id = :challengeId
                FOR UPDATE
                """, new MapSqlParameterSource(CHALLENGE_ID, challengeId),
                (resultSet, rowNumber) -> new StakeSetSnapshot(
                        resultSet.getString("state"),
                        resultSet.getLong("set_version"),
                        resultSet.getString("digest_hex")))
                .stream()
                .findFirst();
    }

    public Optional<SettlementSnapshot> findSettlementByResultId(UUID resultId) {
        return settlement("""
                SELECT id, result_id, challenge_id, set_version, rule_version, pool, phase,
                       winner_count, encode(winners_digest, 'hex') AS winners_digest_hex
                FROM coin_settlements
                WHERE result_id = :resultId
                """, RESULT_ID, resultId);
    }

    public Optional<SettlementSnapshot> findSettlementByChallengeId(UUID challengeId) {
        return settlement("""
                SELECT id, result_id, challenge_id, set_version, rule_version, pool, phase,
                       winner_count, encode(winners_digest, 'hex') AS winners_digest_hex
                FROM coin_settlements
                WHERE challenge_id = :challengeId
                """, CHALLENGE_ID, challengeId);
    }

    private Optional<SettlementSnapshot> settlement(String sql, String parameterName, UUID parameter) {
        return jdbc.query(sql, new MapSqlParameterSource(parameterName, parameter),
                (resultSet, rowNumber) -> new SettlementSnapshot(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("result_id", UUID.class),
                        resultSet.getObject("challenge_id", UUID.class),
                        resultSet.getLong("set_version"),
                        resultSet.getString("rule_version"),
                        resultSet.getLong("pool"),
                        resultSet.getString("phase"),
                        resultSet.getLong("winner_count"),
                        resultSet.getString("winners_digest_hex")))
                .stream()
                .findFirst();
    }

    public int insertSettlement(UUID id, UUID resultId, UUID challengeId, long setVersion,
                                String ruleVersion, long pool, long winnerCount, byte[] winnersDigest) {
        return jdbc.update("""
                INSERT INTO coin_settlements
                    (id, result_id, challenge_id, set_version, rule_version, pool, phase,
                     winner_count, winners_digest, winners_loaded, next_attempt_at)
                VALUES (:id, :resultId, :challengeId, :setVersion, :ruleVersion, :pool, 'DISTRIBUTING',
                        :winnerCount, :winnersDigest, :winnerCount, CURRENT_TIMESTAMP)
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue(RESULT_ID, resultId)
                .addValue(CHALLENGE_ID, challengeId)
                .addValue("setVersion", setVersion)
                .addValue("ruleVersion", ruleVersion)
                .addValue("pool", pool)
                .addValue("winnerCount", winnerCount)
                .addValue("winnersDigest", winnersDigest));
    }

    public List<SealedHold> findSealedStakes(UUID challengeId, long setVersion) {
        return jdbc.query("""
                SELECT hold_id, user_id, amount
                FROM coin_sealed_stakes
                WHERE challenge_id = :challengeId AND set_version = :setVersion
                ORDER BY hold_id
                """, new MapSqlParameterSource()
                        .addValue(CHALLENGE_ID, challengeId)
                        .addValue("setVersion", setVersion),
                (resultSet, rowNumber) -> new SealedHold(
                        resultSet.getObject("hold_id", UUID.class),
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getLong(AMOUNT)));
    }

    public List<ActiveHold> lockActiveHolds(UUID challengeId) {
        return jdbc.query("""
                SELECT id, user_id, amount
                FROM coin_holds
                WHERE challenge_id = :challengeId AND status = 'ACTIVE'
                ORDER BY id
                FOR UPDATE
                """, new MapSqlParameterSource(CHALLENGE_ID, challengeId),
                (resultSet, rowNumber) -> new ActiveHold(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getLong(AMOUNT)));
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

    public void markHoldSettled(UUID holdId) {
        jdbc.update("""
                UPDATE coin_holds
                SET status = 'SETTLED', updated_at = CURRENT_TIMESTAMP
                WHERE id = :holdId
                """, new MapSqlParameterSource(HOLD_ID, holdId));
    }

    public int markStakeSetSettled(UUID challengeId, long setVersion) {
        return jdbc.update("""
                UPDATE coin_stake_sets
                SET state = 'SETTLED', updated_at = CURRENT_TIMESTAMP
                WHERE challenge_id = :challengeId
                  AND state = 'SEALED'
                  AND set_version = :setVersion
                """, new MapSqlParameterSource()
                .addValue(CHALLENGE_ID, challengeId)
                .addValue("setVersion", setVersion));
    }

    public int completeSettlement(UUID settlementId, long winnerCount) {
        return jdbc.update("""
                UPDATE coin_settlements
                SET phase = 'COMPLETED',
                    winners_loaded = :winnerCount,
                    lease_owner = NULL,
                    lease_until = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :settlementId AND phase = 'DISTRIBUTING'
                """, new MapSqlParameterSource()
                .addValue(SETTLEMENT_ID, settlementId)
                .addValue("winnerCount", winnerCount));
    }

    public void insertSettlementWinner(UUID settlementId, UUID userId) {
        jdbc.update("""
                INSERT INTO coin_settlement_winners (settlement_id, user_id)
                VALUES (:settlementId, :userId)
                """, new MapSqlParameterSource()
                .addValue(SETTLEMENT_ID, settlementId)
                .addValue(USER_ID, userId));
    }

    public void insertAllocation(UUID settlementId, UUID userId, long amount) {
        jdbc.update("""
                INSERT INTO coin_settlement_allocations
                    (settlement_id, user_id, amount, remainder_bonus, status)
                VALUES (:settlementId, :userId, :amount, 0, 'PAID')
                """, new MapSqlParameterSource()
                .addValue(SETTLEMENT_ID, settlementId)
                .addValue(USER_ID, userId)
                .addValue(AMOUNT, amount));
    }

    public void insertHoldMove(UUID settlementId, UUID holdId, long amount) {
        jdbc.update("""
                INSERT INTO coin_settlement_hold_moves (settlement_id, hold_id, amount)
                VALUES (:settlementId, :holdId, :amount)
                """, new MapSqlParameterSource()
                .addValue(SETTLEMENT_ID, settlementId)
                .addValue(HOLD_ID, holdId)
                .addValue(AMOUNT, amount));
    }

    public void insertLedgerEntry(UUID id, UUID userId, UUID operationId, int entryIndex,
                                  long availableDelta, long lockedDelta, UUID challengeId) {
        jdbc.update("""
                INSERT INTO coin_ledger_entries
                    (id, user_id, operation_id, entry_index, type,
                     available_delta, locked_delta, challenge_id)
                VALUES (:id, :userId, :operationId, :entryIndex, 'SETTLEMENT',
                        :availableDelta, :lockedDelta, :challengeId)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue(USER_ID, userId)
                .addValue("operationId", operationId)
                .addValue("entryIndex", entryIndex)
                .addValue("availableDelta", availableDelta)
                .addValue("lockedDelta", lockedDelta)
                .addValue(CHALLENGE_ID, challengeId));
    }

    public record StakeSetSnapshot(String state, long setVersion, String digestHex) {
    }

    public record SettlementSnapshot(UUID id, UUID resultId, UUID challengeId, long setVersion,
                                     String ruleVersion, long pool, String phase, long winnerCount,
                                     String winnersDigestHex) {
    }

    public record SealedHold(UUID holdId, UUID userId, long amount) {
    }

    public record ActiveHold(UUID holdId, UUID userId, long amount) {
    }

    public record WalletBalances(long availableBalance, long lockedBalance) {
    }
}
