package io.github.returnaction.fitness.identity.registration.repository;

import io.github.returnaction.fitness.identity.registration.entity.RoleCode;
import io.github.returnaction.fitness.identity.registration.entity.RoleEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<RoleEntity, Short> {

    Optional<RoleEntity> findByCode(RoleCode code);
}
