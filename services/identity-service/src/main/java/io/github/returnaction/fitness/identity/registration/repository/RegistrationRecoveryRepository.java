package io.github.returnaction.fitness.identity.registration.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.returnaction.fitness.identity.registration.exception.RecoveryConflictException;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class RegistrationRecoveryRepository {

    private final EntityManager entityManager;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring injects a transaction-scoped EntityManager proxy")
    public RegistrationRecoveryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Row find(UUID id, boolean lock) {
        String sql = "SELECT id, status, version, recovery_blocked_at, last_error_code "
                + "FROM registration_operations WHERE id = :id" + (lock ? " FOR UPDATE" : "");
        var rows = entityManager.createNativeQuery(sql).setParameter("id", id).getResultList();
        if (rows.isEmpty())
            throw new RecoveryConflictException("not_found");
        Object[] row = (Object[]) rows.getFirst();
        Instant blocked = (Instant) row[3];
        return new Row((UUID) row[0], (String) row[1], ((Number) row[2]).longValue(), blocked, (String) row[4]);
    }

    public int resume(UUID operationId) {
        return entityManager
                .createNativeQuery("""
                        UPDATE registration_operations
                        SET recovery_blocked_at = NULL, last_error_code = NULL,
                            next_retry_at = clock_timestamp(), lease_owner = NULL, lease_until = NULL,
                            lease_version = lease_version + 1, version = version + 1, updated_at = clock_timestamp()
                        WHERE id = :id AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                        """)
                .setParameter("id", operationId)
                .executeUpdate();
    }

    public void writeAudit(UUID operationId, UUID actorUserId, String reason, String previousErrorCode) {
        entityManager
                .createNativeQuery("""
                        INSERT INTO registration_recovery_audit
                            (id, operation_id, actor_user_id, reason, previous_error_code)
                        VALUES (:id, :operationId, :actor, :reason, :error)
                        """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("operationId", operationId)
                .setParameter("actor", actorUserId)
                .setParameter("reason", reason)
                .setParameter("error", previousErrorCode)
                .executeUpdate();
    }

    public record Row(UUID operationId, String status, long version, Instant recoveryBlockedAt, String lastErrorCode) {
    }
}
