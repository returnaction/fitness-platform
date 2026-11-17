package io.github.returnaction.fitness.challenge.entity;

import io.github.returnaction.fitness.challenge.domain.ChallengeOperationKind;
import io.github.returnaction.fitness.challenge.domain.ChallengeOperationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "challenge_operations")
public class ChallengeOperationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "challenge_id", nullable = false, updatable = false)
    private UUID challengeId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private ChallengeOperationKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ChallengeOperationStatus status;

    @Column(name = "hold_id")
    private UUID holdId;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ChallengeOperationEntity(UUID challengeId, UUID userId, ChallengeOperationKind kind, Instant now) {
        this.challengeId = challengeId;
        this.userId = userId;
        this.kind = kind;
        status = ChallengeOperationStatus.PENDING;
        createdAt = now;
        updatedAt = now;
    }

    public void confirm(UUID holdId, Instant now) {
        this.holdId = holdId;
        status = ChallengeOperationStatus.CONFIRMED;
        updatedAt = now;
    }
}
