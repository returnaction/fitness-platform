package io.github.returnaction.fitness.challenge.entity;

import io.github.returnaction.fitness.challenge.domain.ChallengeMode;
import io.github.returnaction.fitness.challenge.domain.ChallengeStatus;
import io.github.returnaction.fitness.challenge.domain.ChallengeVisibility;
import io.github.returnaction.fitness.challenge.domain.DraftChanges;
import io.github.returnaction.fitness.challenge.domain.StartMode;
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
@Table(name = "challenges")
public class ChallengeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "creator_user_id", nullable = false, updatable = false)
    private UUID creatorUserId;

    @Column(name = "title")
    private String title;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", length = 32)
    private ChallengeMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ChallengeStatus status;

    @Column(name = "stake_amount")
    private Long stakeAmount;

    @Column(name = "min_participants")
    private Integer minParticipants;

    @Column(name = "max_participants")
    private Integer maxParticipants;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 16)
    private ChallengeVisibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "start_mode", length = 16)
    private StartMode startMode;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(name = "next_join_sequence", nullable = false)
    private long nextJoinSequence;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ChallengeEntity(UUID creatorUserId, Instant now) {
        this.creatorUserId = creatorUserId;
        status = ChallengeStatus.DRAFT;
        visibility = ChallengeVisibility.PUBLIC;
        nextJoinSequence = 1;
        version = 1;
        createdAt = now;
        updatedAt = now;
    }

    /**
     * Резервирует следующий номер подтверждённого участника. Вызывается только
     * под пессимистической блокировкой строки challenge.
     */
    public long takeNextJoinSequence(Instant now) {
        long sequence = nextJoinSequence;
        nextJoinSequence = sequence + 1;
        version++;
        updatedAt = now;
        return sequence;
    }

    /**
     * Переводит черновик в OPEN и резервирует первый номер участника.
     * Вызывается под блокировкой строки после подтверждённого резерва Coins.
     */
    public long openForJoining(Instant now) {
        status.requireTransitionTo(ChallengeStatus.OPEN);
        mode = ChallengeMode.HEAD_TO_HEAD;
        startMode = StartMode.WHEN_FULL;
        minParticipants = 2;
        maxParticipants = 2;
        long sequence = nextJoinSequence;
        nextJoinSequence = sequence + 1;
        status = ChallengeStatus.OPEN;
        version++;
        updatedAt = now;
        return sequence;
    }

    /**
     * Отменяет опубликованный challenge. Вызывается под блокировкой строки,
     * только пока нет других подтверждённых участников.
     */
    public void cancel(Instant now) {
        status.requireTransitionTo(ChallengeStatus.CANCELLED);
        status = ChallengeStatus.CANCELLED;
        version++;
        updatedAt = now;
    }

    /**
     * Применяет частичное изменение черновика. Вызывается только после проверки
     * состояния, владельца и ожидаемой версии под блокировкой строки.
     */
    public void updateDraft(DraftChanges changes, Instant now) {
        if (changes.titlePresent()) {
            title = changes.title();
        }
        if (changes.descriptionPresent()) {
            description = changes.description();
        }
        if (changes.stakeAmountPresent()) {
            stakeAmount = changes.stakeAmount();
        }
        if (changes.durationDaysPresent()) {
            durationDays = changes.durationDays();
        }
        version++;
        updatedAt = now;
    }
}
