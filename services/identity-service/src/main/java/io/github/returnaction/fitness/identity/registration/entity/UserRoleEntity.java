package io.github.returnaction.fitness.identity.registration.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "user_roles")
public class UserRoleEntity {

    @EmbeddedId
    private UserRoleId id;

    private Instant createdAt;

    protected UserRoleEntity() {}

    public UserRoleEntity(UserRoleId id) {
        this.id = id;
    }

    @PrePersist
    void createTimestamp() {
        createdAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
