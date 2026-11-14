package io.github.returnaction.fitness.identity.registration.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.returnaction.fitness.identity.registration.exception.RecoveryConflictException;
import io.github.returnaction.fitness.identity.registration.repository.RegistrationRecoveryRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Внутренний слой: вызывающий обязан заранее проверить
 * identity.registration.recover.
 */
@Service
public class RegistrationRecoveryService {

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring-managed repository is shared and not exposed.")
    private final RegistrationRecoveryRepository repository;

    public RegistrationRecoveryService(RegistrationRecoveryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public State read(UUID operationId) {
        return find(operationId, false);
    }

    @Transactional
    public State resume(UUID operationId, UUID actorUserId, long expectedVersion, String reason) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        if (expectedVersion < 1 || reason == null || reason.isBlank()
                || reason.codePointCount(0, reason.length()) > 500) {
            throw new IllegalArgumentException("Invalid recovery request");
        }
        State state = find(operationId, true);
        if (state.version() != expectedVersion)
            throw new RecoveryConflictException("version_conflict");
        if (!"PENDING".equals(state.status()) || state.recoveryBlockedAt() == null) {
            throw new RecoveryConflictException("operation_not_blocked");
        }
        int updated = repository.resume(operationId);
        if (updated != 1)
            throw new RecoveryConflictException("operation_busy");
        repository.writeAudit(operationId, actorUserId, reason, state.lastErrorCode());
        return find(operationId, false);
    }

    private State find(UUID id, boolean lock) {
        Objects.requireNonNull(id, "operationId");
        var row = repository.find(id, lock);
        return new State(row.operationId(), row.status(), row.version(), row.recoveryBlockedAt(), row.lastErrorCode());
    }

    public record State(UUID operationId,
                        String status,
                        long version,
                        Instant recoveryBlockedAt,
                        String lastErrorCode) {
    }
}
