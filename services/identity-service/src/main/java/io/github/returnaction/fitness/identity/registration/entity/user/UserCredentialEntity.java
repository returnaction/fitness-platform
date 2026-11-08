package io.github.returnaction.fitness.identity.registration.entity.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "user_credentials")
public class UserCredentialEntity {

    @Id
    @Column(name = "user_id")
    @Getter
    private UUID userId;

    @Column(name = "password_hash", nullable = false, length = 255)
    @Getter
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserCredentialEntity() {
    }

    public UserCredentialEntity(UUID userId, String passwordHash) {
        this.userId = userId;
        this.passwordHash = passwordHash;
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
}
