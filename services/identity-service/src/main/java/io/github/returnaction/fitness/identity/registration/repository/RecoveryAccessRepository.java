package io.github.returnaction.fitness.identity.registration.repository;

import io.github.returnaction.fitness.identity.registration.entity.user.UserEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface RecoveryAccessRepository extends Repository<UserEntity, UUID> {

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id
                JOIN users u ON u.id=ur.user_id
                WHERE u.id=:user AND u.status='ACTIVE' AND r.code='SUPER_ADMIN')
            """, nativeQuery = true)
    boolean isActiveSuperAdmin(@Param("user") UUID userId);
}
