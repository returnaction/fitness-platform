package io.github.returnaction.fitness.identity.registration.recovery;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Внутренний слой: вызывающий обязан заранее проверить identity.registration.recover. */
@Service
public class RegistrationRecoveryService {
    private final JdbcClient jdbc;

    public RegistrationRecoveryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public State read(UUID operationId) {
        return find(operationId, false);
    }

    @Transactional
    public State resume(UUID operationId, UUID actorUserId, long expectedVersion, String reason) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        if (expectedVersion < 1
                || reason == null
                || reason.isBlank()
                || reason.codePointCount(0, reason.length()) > 500) {
            throw new IllegalArgumentException("Invalid recovery request");
        }
        State state = find(operationId, true);
        if (state.version() != expectedVersion) throw new RecoveryConflictException("version_conflict");
        if (!"PENDING".equals(state.status()) || state.recoveryBlockedAt() == null) {
            throw new RecoveryConflictException("operation_not_blocked");
        }
        int updated = jdbc.sql("""
                UPDATE registration_operations
                SET recovery_blocked_at = NULL, last_error_code = NULL,
                    next_retry_at = clock_timestamp(), lease_owner = NULL, lease_until = NULL,
                    lease_version = lease_version + 1, version = version + 1, updated_at = clock_timestamp()
                WHERE id = :id AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                """).param("id", operationId).update();
        if (updated != 1) throw new RecoveryConflictException("operation_busy");
        jdbc.sql("""
                INSERT INTO registration_recovery_audit
                    (id, operation_id, actor_user_id, reason, previous_error_code)
                VALUES (:id, :operationId, :actor, :reason, :error)
                """)
                .param("id", UUID.randomUUID())
                .param("operationId", operationId)
                .param("actor", actorUserId)
                .param("reason", reason)
                .param("error", state.lastErrorCode())
                .update();
        return find(operationId, false);
    }

    private State find(UUID id, boolean lock) {
        Objects.requireNonNull(id, "operationId");
        String sql = "SELECT id, status, version, recovery_blocked_at, last_error_code "
                + "FROM registration_operations WHERE id = :id" + (lock ? " FOR UPDATE" : "");
        return jdbc.sql(sql)
                .param("id", id)
                .query((row, index) -> {
                    OffsetDateTime blocked = row.getObject("recovery_blocked_at", OffsetDateTime.class);
                    return new State(
                            row.getObject("id", UUID.class),
                            row.getString("status"),
                            row.getLong("version"),
                            blocked == null ? null : blocked.toInstant(),
                            row.getString("last_error_code"));
                })
                .optional()
                .orElseThrow(() -> new RecoveryConflictException("not_found"));
    }

    public record State(
            UUID operationId, String status, long version, Instant recoveryBlockedAt, String lastErrorCode) {}
}
