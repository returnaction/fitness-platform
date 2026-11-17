package io.github.returnaction.fitness.coins.dailyreward.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Факт получения подарка. От claimedAt сервис отсчитывает 24 часа до следующего получения.
 */
@Entity
@Table(name = "daily_reward_claims")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyRewardClaimEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "claimed_at", nullable = false, updatable = false)
    private Instant claimedAt;

    @Column(name = "reward_amount", nullable = false, updatable = false)
    private long rewardAmount;

    @Column(name = "ledger_entry_id", nullable = false, unique = true, updatable = false)
    private UUID ledgerEntryId;

    public DailyRewardClaimEntity(UUID id,
                                  UUID userId,
                                  Instant claimedAt,
                                  long rewardAmount,
                                  UUID ledgerEntryId) {
        this.id = id;
        this.userId = userId;
        this.claimedAt = claimedAt;
        this.rewardAmount = rewardAmount;
        this.ledgerEntryId = ledgerEntryId;
    }
}
