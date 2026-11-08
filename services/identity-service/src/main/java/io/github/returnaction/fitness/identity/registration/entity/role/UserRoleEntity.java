package io.github.returnaction.fitness.identity.registration.entity.role;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

@Entity
@Table(name = "user_roles")
public class UserRoleEntity {

    @EmbeddedId
    private UserRoleId id;

    @Getter
    private Instant createdAt;

    protected UserRoleEntity() {
    }

    public UserRoleEntity(UserRoleId id) {
        this.id = id;
    }

    @PrePersist
    void createTimestamp() {
        createdAt = Instant.now();
    }
}
