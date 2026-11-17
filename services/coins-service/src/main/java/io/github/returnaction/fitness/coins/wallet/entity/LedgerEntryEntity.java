package io.github.returnaction.fitness.coins.wallet.entity;

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

/**
 * Неизменяемая запись журнала Coins. Исправления выполняются новой записью.
 */
@Entity
@Table(name = "coin_ledger_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntryEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "operation_id", nullable = false, updatable = false)
    private UUID operationId;

    @Column(name = "entry_index", nullable = false, updatable = false)
    private int entryIndex; // TODO почему int  а не INTEGER?

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 32, updatable = false)
    private LedgerEntryType entryType;

    @Column(name = "available_delta", nullable = false, updatable = false)
    private long availableDelta; // TODO почему long а не Long

    @Column(name = "locked_delta", nullable = false, updatable = false)
    private long lockedDelta; // TODO почему long а не Long

    @Column(name = "challenge_id", updatable = false)
    private UUID challengeId;

    @Column(name = "reverses_entry_id", updatable = false)
    private UUID reversesEntryId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public LedgerEntryEntity(UUID id,
                             UUID userId,
                             UUID operationId,
                             int entryIndex,
                             LedgerEntryType entryType,
                             long availableDelta,
                             long lockedDelta,
                             UUID challengeId,
                             UUID reversesEntryId,
                             Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.operationId = operationId;
        this.entryIndex = entryIndex;
        this.entryType = entryType;
        this.availableDelta = availableDelta;
        this.lockedDelta = lockedDelta;
        this.challengeId = challengeId;
        this.reversesEntryId = reversesEntryId;
        this.createdAt = createdAt;
    }
}
