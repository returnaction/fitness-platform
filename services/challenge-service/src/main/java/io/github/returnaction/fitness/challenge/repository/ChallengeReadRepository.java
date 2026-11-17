package io.github.returnaction.fitness.challenge.repository;

import io.github.returnaction.fitness.challenge.domain.SettlementStatus;
import java.util.Map;
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
}
