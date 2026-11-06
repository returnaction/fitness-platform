package io.github.returnaction.fitness.identity.registration.repository;

import io.github.returnaction.fitness.identity.registration.entity.UserRoleEntity;
import io.github.returnaction.fitness.identity.registration.entity.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRoleEntity, UserRoleId> {}
