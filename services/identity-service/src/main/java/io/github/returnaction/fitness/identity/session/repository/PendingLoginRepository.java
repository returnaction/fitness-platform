package io.github.returnaction.fitness.identity.session.repository;

import io.github.returnaction.fitness.identity.registration.entity.user.UserEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PendingLoginRepository extends Repository<UserEntity, UUID> {
    @Query(value = """
            SELECT u.id AS id, u.status AS status, c.password_hash AS "passwordHash" FROM users u
            JOIN user_credentials c ON c.user_id = u.id WHERE u.email_canonical = :email
            """, nativeQuery = true)
    Optional<UserProjection> findUserProjection(@Param("email") String email);

    default User findUser(String email) {
        return findUserProjection(email)
                .map(row -> new User(row.getId(), row.getStatus(), row.getPasswordHash()))
                .orElse(null);
    }

    @Transactional
    @Modifying
    @Query(value = """
            UPDATE registration_operations o
            SET next_retry_at = clock_timestamp(), version = version + 1, updated_at = clock_timestamp()
            FROM users u, user_credentials c
            WHERE o.user_id = :user AND u.id = o.user_id AND u.status = 'PENDING'
              AND c.user_id = u.id AND c.password_hash = :hash
              AND o.status = 'PENDING' AND o.recovery_blocked_at IS NULL
              AND (o.lease_until IS NULL OR o.lease_until <= clock_timestamp())
              AND o.next_retry_at > clock_timestamp()
            """, nativeQuery = true)
    void accelerate(@Param("user") UUID userId, @Param("hash") String hash);

    interface UserProjection {
        UUID getId();

        String getStatus();

        String getPasswordHash();
    }

    record User(UUID id, String status, String passwordHash) {
        @Override
        public String toString() {
            return "User[redacted]";
        }
    }
}
