package io.github.returnaction.fitness.identity.registration.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class RegistrationSessionRepository {

    private final EntityManager entityManager;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring injects a transaction-scoped EntityManager proxy")
    public RegistrationSessionRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Credentials lockRegistration(UUID operationId) {
        var userIds = entityManager.createNativeQuery("SELECT user_id FROM registration_operations WHERE id=:id")
                .setParameter("id", operationId)
                .getResultList();
        if (userIds.isEmpty())
            throw new IllegalArgumentException("Registration is unavailable");
        // Единый порядок: пользователь, затем операция регистрации.
        var users = entityManager.createNativeQuery("SELECT id FROM users WHERE id=:id FOR UPDATE")
                .setParameter("id", userIds.getFirst())
                .getResultList();
        if (users.isEmpty())
            throw new IllegalArgumentException("Registration is unavailable");
        var rows = entityManager.createNativeQuery("""
                SELECT u.id, u.status AS user_status, o.status AS operation_status, c.password_hash
                FROM registration_operations o
                JOIN users u ON u.id = o.user_id
                JOIN user_credentials c ON c.user_id = u.id
                WHERE o.id = :operationId
                FOR UPDATE OF o
                """).setParameter("operationId", operationId).getResultList();
        if (rows.isEmpty())
            throw new IllegalArgumentException("Registration is unavailable");
        Object[] row = (Object[]) rows.getFirst();
        return new Credentials((UUID) row[0], (String) row[1], (String) row[2], (String) row[3]);
    }

    public void revokeRegistrationSessions(UUID operationId) {
        entityManager.createNativeQuery("""
                UPDATE auth_sessions SET revoked_at = clock_timestamp()
                WHERE registration_operation_id = :operationId AND revoked_at IS NULL
                """).setParameter("operationId", operationId).executeUpdate();
    }

    public boolean lockActiveUser(UUID userId) {
        return (Boolean) entityManager.createNativeQuery("""
                SELECT EXISTS (SELECT 1 FROM users WHERE id=:id AND status='ACTIVE' FOR UPDATE)
                """).setParameter("id", userId).getSingleResult();
    }

    public String lockPasswordHash(UUID userId) {
        var rows = entityManager
                .createNativeQuery("SELECT password_hash FROM user_credentials WHERE user_id=:id FOR UPDATE")
                .setParameter("id", userId)
                .getResultList();
        return rows.isEmpty() ? null : (String) rows.getFirst();
    }

    public void updatePasswordHash(UUID userId, String hash) {
        entityManager.createNativeQuery(
                "UPDATE user_credentials SET password_hash=:hash, updated_at=clock_timestamp() WHERE user_id=:id")
                .setParameter("id", userId)
                .setParameter("hash", hash)
                .executeUpdate();
    }

    public Instant insertSession(UUID sessionId, UUID userId, UUID operationId) {
        return (Instant) entityManager.createNativeQuery("""
                INSERT INTO auth_sessions (id, user_id, registration_operation_id, expires_at)
                VALUES (:id, :userId, :operationId, CURRENT_TIMESTAMP + INTERVAL '30 days')
                RETURNING expires_at
                """)
                .setParameter("id", sessionId)
                .setParameter("userId", userId)
                .setParameter("operationId", operationId)
                .getSingleResult();
    }

    public void insertRefresh(UUID tokenId, UUID sessionId, byte[] hash) {
        entityManager.createNativeQuery("""
                INSERT INTO refresh_tokens (id, session_id, secret_hash, expires_at)
                SELECT :id, id, :hash, expires_at FROM auth_sessions WHERE id = :sessionId
                """)
                .setParameter("id", tokenId)
                .setParameter("hash", hash)
                .setParameter("sessionId", sessionId)
                .executeUpdate();
    }

    public record Credentials(UUID userId,
                              String userStatus,
                              String operationStatus,
                              String passwordHash) {

        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }
}
