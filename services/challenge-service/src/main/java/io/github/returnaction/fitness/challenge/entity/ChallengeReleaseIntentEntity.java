package io.github.returnaction.fitness.challenge.entity;

import io.github.returnaction.fitness.challenge.domain.ReleaseIntentStatus;
import io.github.returnaction.fitness.challenge.domain.ReleaseReason;
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
@Table(name = "challenge_release_intents")
public class ChallengeReleaseIntentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "challenge_id", nullable = false, updatable = false)
    private UUID challengeId;

    @Column(name = "hold_id", nullable = false, updatable = false)
    private UUID holdId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32)
    private ReleaseReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReleaseIntentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ChallengeReleaseIntentEntity(UUID challengeId, UUID holdId, ReleaseReason reason, Instant now) {
        this.challengeId = challengeId;
        this.holdId = holdId;
        this.reason = reason;
        status = ReleaseIntentStatus.PENDING;
        createdAt = now;
        updatedAt = now;
    }

    public void confirm(Instant now) {
        status = ReleaseIntentStatus.CONFIRMED;
        updatedAt = now;
    }
}
