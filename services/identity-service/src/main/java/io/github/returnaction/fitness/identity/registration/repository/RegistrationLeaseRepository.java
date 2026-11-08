package io.github.returnaction.fitness.identity.registration.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Короткие транзакции аренды операций; сетевые вызовы выполняются отдельно. */
@Repository
public class RegistrationLeaseRepository {
    private final EntityManager entityManager;
    private final double retryInitialSeconds;
    private final double retryMaxSeconds;
    private final double jitterFraction;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring injects a transaction-scoped EntityManager proxy")
    public RegistrationLeaseRepository(
                                       EntityManager entityManager,
                                       @Value("${identity.registration.worker.retry-initial-seconds}") double retryInitialSeconds,
                                       @Value("${identity.registration.worker.retry-max-seconds}") double retryMaxSeconds,
                                       @Value("${identity.registration.worker.jitter-fraction}") double jitterFraction) {
        this.entityManager = entityManager;
        this.retryInitialSeconds = retryInitialSeconds;
        this.retryMaxSeconds = retryMaxSeconds;
        this.jitterFraction = jitterFraction;
    }

    @Transactional
    public List<Lease> claim(UUID owner, int limit, int leaseSeconds) {
        return claimStage(owner, limit, leaseSeconds, false);
    }

    @Transactional
    public List<Lease> claimWallet(UUID owner, int limit, int leaseSeconds) {
        return claimStage(owner, limit, leaseSeconds, true);
    }

    private List<Lease> claimStage(UUID owner, int limit, int leaseSeconds, boolean walletStage) {
        if (owner == null || limit < 1 || leaseSeconds < 1) {
            throw new IllegalArgumentException("Invalid lease configuration");
        }
        var rows = entityManager
                .createNativeQuery("""
                        WITH candidates AS (
                            SELECT id FROM registration_operations
                            WHERE status = 'PENDING' AND recovery_blocked_at IS NULL
                              AND ((:walletStage = FALSE AND NOT profile_ready)
                                   OR (:walletStage = TRUE AND profile_ready AND NOT wallet_ready))
                              AND next_retry_at <= clock_timestamp()
                              AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                            ORDER BY next_retry_at, id FOR UPDATE SKIP LOCKED LIMIT :limit
                        )
                        UPDATE registration_operations operation
                        SET lease_owner = :owner, lease_until = clock_timestamp() + make_interval(secs => :seconds),
                            lease_version = operation.lease_version + 1, version = operation.version + 1,
                            updated_at = clock_timestamp()
                        FROM candidates WHERE operation.id = candidates.id
                        RETURNING operation.id, operation.user_id, operation.lease_version
                        """)
                .setParameter("owner", owner)
                .setParameter("limit", limit)
                .setParameter("seconds", leaseSeconds)
                .setParameter("walletStage", walletStage)
                .getResultList();
        return rows.stream()
                .map(value -> {
                    Object[] row = (Object[]) value;
                    return new Lease((UUID) row[0], (UUID) row[1], owner, ((Number) row[2]).longValue());
                })
                .toList();
    }

    @Transactional
    public boolean markProfileReady(Lease lease) {
        return entityManager
                .createNativeQuery("""
                        UPDATE registration_operations
                        SET profile_ready = TRUE, lease_owner = NULL, lease_until = NULL, version = version + 1,
                            updated_at = clock_timestamp()
                        WHERE id = :id AND status = 'PENDING' AND lease_owner = :owner
                          AND lease_version = :version AND lease_until > clock_timestamp()
                        """)
                .setParameter("id", lease.operationId())
                .setParameter("owner", lease.owner())
                .setParameter("version", lease.version())
                .executeUpdate() == 1;
    }

    @Transactional
    public boolean markWalletReady(Lease lease) {
        int updated = entityManager
                .createNativeQuery(
                        """
                                UPDATE registration_operations
                                SET wallet_ready = TRUE, status = 'COMPLETED', completed_at = clock_timestamp(),
                                    lease_owner = NULL, lease_until = NULL, version = version + 1, updated_at = clock_timestamp()
                                WHERE id = :id AND user_id = :userId AND status = 'PENDING' AND profile_ready AND lease_owner = :owner
                                  AND lease_version = :version AND lease_until > clock_timestamp()
                                """)
                .setParameter("id", lease.operationId())
                .setParameter("userId", lease.userId())
                .setParameter("owner", lease.owner())
                .setParameter("version", lease.version())
                .executeUpdate();
        if (updated == 0) {
            return false;
        }
        int activated = entityManager
                .createNativeQuery("""
                        UPDATE users SET status = 'ACTIVE', updated_at = clock_timestamp()
                        WHERE id = :userId AND status = 'PENDING'
                        """)
                .setParameter("userId", lease.userId())
                .executeUpdate();
        if (activated != 1) {
            throw new IllegalStateException("Registration user cannot be activated");
        }
        return true;
    }

    @Transactional
    public boolean recordFailure(Lease lease, boolean retryable, String errorCode) {
        if (retryInitialSeconds <= 0
                || retryMaxSeconds < retryInitialSeconds
                || jitterFraction < 0
                || jitterFraction > 1) {
            throw new IllegalArgumentException("Invalid retry configuration");
        }
        return entityManager
                .createNativeQuery(
                        """
                                UPDATE registration_operations
                                SET retry_count = retry_count + 1,
                                    next_retry_at = CASE WHEN :retryable THEN clock_timestamp()
                                        + make_interval(secs => LEAST(:retryMax, :retryInitial * power(2.0, LEAST(retry_count, 30))
                                            * (1.0 + random() * :jitter))) ELSE next_retry_at END,
                                    recovery_blocked_at = CASE WHEN :retryable THEN NULL ELSE clock_timestamp() END,
                                    last_error_code = :errorCode, lease_owner = NULL, lease_until = NULL, version = version + 1,
                                    updated_at = clock_timestamp()
                                WHERE id = :id AND status = 'PENDING' AND lease_owner = :owner
                                  AND lease_version = :version AND lease_until > clock_timestamp()
                                """)
                .setParameter("retryable", retryable)
                .setParameter("retryMax", retryMaxSeconds)
                .setParameter("retryInitial", retryInitialSeconds)
                .setParameter("jitter", jitterFraction)
                .setParameter("errorCode", errorCode)
                .setParameter("id", lease.operationId())
                .setParameter("owner", lease.owner())
                .setParameter("version", lease.version())
                .executeUpdate() == 1;
    }

    public record Lease(UUID operationId, UUID userId, UUID owner, long version) {
    }
}
