package io.github.returnaction.fitness.identity.registration.entity.user;

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
import lombok.Getter;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @Getter
    private UUID id;

    @Column(name = "email_canonical", nullable = false, length = 254)
    @Getter
    private String emailCanonical;

    @Column(name = "email_version", nullable = false)
    private long emailVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Getter
    private UserStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserEntity() {
    }

    public UserEntity(UUID id, String emailCanonical, UserStatus status) {
        this.id = id;
        this.emailCanonical = emailCanonical;
        this.emailVersion = 1;
        this.status = status;
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
