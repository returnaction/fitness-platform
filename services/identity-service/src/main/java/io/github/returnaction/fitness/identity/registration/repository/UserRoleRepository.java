package io.github.returnaction.fitness.identity.registration.repository;

import io.github.returnaction.fitness.identity.registration.entity.role.UserRoleEntity;
import io.github.returnaction.fitness.identity.registration.entity.role.UserRoleId;
import io.github.returnaction.fitness.identity.registration.entity.role.RoleCode;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRoleRepository extends JpaRepository<UserRoleEntity, UserRoleId> {
    @Query("""
            select r.code from UserRoleEntity ur, RoleEntity r
            where ur.id.userId = :userId and r.id = ur.id.roleId
            order by r.code
            """)
    List<RoleCode> findRoleCodesByUserId(UUID userId);
}
