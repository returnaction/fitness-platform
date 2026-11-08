package io.github.returnaction.fitness.identity.registration.repository;

import io.github.returnaction.fitness.identity.registration.entity.role.RoleCode;
import io.github.returnaction.fitness.identity.registration.entity.role.RoleEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<RoleEntity, Short> {

    Optional<RoleEntity> findByCode(RoleCode code);
}
