package io.github.returnaction.fitness.identity.registration.repository;

import io.github.returnaction.fitness.identity.registration.entity.role.UserRoleEntity;
import io.github.returnaction.fitness.identity.registration.entity.role.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRoleEntity, UserRoleId> {
}
