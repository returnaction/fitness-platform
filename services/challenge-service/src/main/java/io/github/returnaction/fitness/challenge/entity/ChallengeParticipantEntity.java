package io.github.returnaction.fitness.challenge.entity;

import io.github.returnaction.fitness.challenge.domain.ParticipationStatus;
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
@Table(name = "challenge_participants")
public class ChallengeParticipantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "challenge_id", nullable = false, updatable = false)
    private UUID challengeId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ParticipationStatus status;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "join_sequence", nullable = false, updatable = false)
    private long joinSequence;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ChallengeParticipantEntity(UUID challengeId, UUID userId, long joinSequence, Instant now) {
        this.challengeId = challengeId;
        this.userId = userId;
        this.joinSequence = joinSequence;
        status = ParticipationStatus.JOINED;
        version = 1;
        joinedAt = now;
        createdAt = now;
        updatedAt = now;
    }

    public void activate(Instant now) {
        status.requireTransitionTo(ParticipationStatus.ACTIVE);
        status = ParticipationStatus.ACTIVE;
        version++;
        updatedAt = now;
    }
}
