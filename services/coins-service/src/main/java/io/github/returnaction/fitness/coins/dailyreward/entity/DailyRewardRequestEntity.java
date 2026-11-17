package io.github.returnaction.fitness.coins.dailyreward.entity;

import io.github.returnaction.fitness.coins.dailyreward.dto.DailyRewardClaimResponse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Окончательный результат первого запроса по Idempotency-Key; изменение и удаление запрещены в БД.
 *
 * <p>Ключ хранится как UUID без хеширования. Снимок ответа — JSONB,
 * чтобы повтор вернул исходный результат, а не текущий баланс.
 * При NOT_YET_AVAILABLE claimId указывает на предыдущее получение,
 * после которого ещё не прошло 24 часа.</p>
 */
@Entity
@Table(name = "daily_reward_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyRewardRequestEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private DailyRewardOutcome outcome;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private UUID claimId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_snapshot", nullable = false, columnDefinition = "jsonb", updatable = false)
    private DailyRewardClaimResponse responseSnapshot;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public DailyRewardRequestEntity(UUID id,
                                    UUID userId,
                                    UUID idempotencyKey,
                                    DailyRewardOutcome outcome,
                                    UUID claimId,
                                    DailyRewardClaimResponse responseSnapshot,
                                    Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.outcome = outcome;
        this.claimId = claimId;
        this.responseSnapshot = responseSnapshot;
        this.createdAt = createdAt;
    }
}
