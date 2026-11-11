package io.github.returnaction.fitness.coins.wallet.entity;

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
 * Кошелёк создаётся атомарным INSERT; значения по умолчанию задаёт PostgreSQL.
 */
@Entity
@Table(name = "coin_wallets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletEntity {
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "available_balance", nullable = false)
    private long availableBalance;

    @Column(name = "locked_balance", nullable = false)
    private long lockedBalance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
