package io.github.returnaction.fitness.identity.registration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "roles")
public class RoleEntity {

    @Id
    private short id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RoleCode code;

    protected RoleEntity() {}

    public short getId() {
        return id;
    }

    public RoleCode getCode() {
        return code;
    }
}
