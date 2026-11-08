package io.github.returnaction.fitness.identity.registration.entity.role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "roles")
public class RoleEntity {

    @Id
    @Getter
    private short id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Getter
    private RoleCode code;

    protected RoleEntity() {
    }
}
