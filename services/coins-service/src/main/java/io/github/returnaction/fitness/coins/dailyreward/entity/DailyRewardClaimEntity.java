package io.github.returnaction.fitness.coins.dailyreward.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Получение ежедневной награды. Дата и время рассчитываются сервером в UTC.
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

    @Column(name = "claim_date", nullable = false, updatable = false)
    private LocalDate claimDate;

    @Column(name = "claimed_at", nullable = false, updatable = false)
    private Instant claimedAt;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(name = "ledger_entry_id", nullable = false, unique = true, updatable = false)
    private UUID ledgerEntryId;

    public DailyRewardClaimEntity(UUID id,
                                  UUID userId,
                                  LocalDate claimDate,
                                  Instant claimedAt,
                                  long amount,
                                  UUID ledgerEntryId) {
        this.id = id;
        this.userId = userId;
        this.claimDate = claimDate;
        this.claimedAt = claimedAt;
        this.amount = amount;
        this.ledgerEntryId = ledgerEntryId;
    }
}
