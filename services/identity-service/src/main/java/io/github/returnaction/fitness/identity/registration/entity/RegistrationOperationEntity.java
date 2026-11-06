package io.github.returnaction.fitness.identity.registration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "registration_operations")
public class RegistrationOperationEntity {

    @Id
    private UUID id;

    @Column(name = "idempotency_key_digest", nullable = false, columnDefinition = "bytea")
    private byte[] idempotencyKeyDigest;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RegistrationOperationStatus status;

    @Column(name = "profile_ready", nullable = false)
    private boolean profileReady;

    @Column(name = "wallet_ready", nullable = false)
    private boolean walletReady;

    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "lease_version", nullable = false)
    private long leaseVersion;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RegistrationOperationEntity() {}

    public RegistrationOperationEntity(UUID id, byte[] idempotencyKeyDigest, UUID userId) {
        this.id = id;
        this.idempotencyKeyDigest = idempotencyKeyDigest.clone();
        this.userId = userId;
        this.status = RegistrationOperationStatus.PENDING;
        this.nextRetryAt = Instant.now();
        this.retryCount = 0;
        this.leaseVersion = 0;
        this.version = 1;
    }

    @PrePersist
    void createTimestamps() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void updateTimestamp() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public byte[] getIdempotencyKeyDigest() {
        return idempotencyKeyDigest.clone();
    }
}
