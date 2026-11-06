package io.github.returnaction.fitness.identity.registration.worker;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Короткие транзакции аренды операций; сетевые вызовы выполняются отдельно. */
@Repository
public class RegistrationLeaseRepository {
    private final JdbcClient jdbc;
    private final double retryInitialSeconds;
    private final double retryMaxSeconds;
    private final double jitterFraction;

    public RegistrationLeaseRepository(
            JdbcClient jdbc,
            @Value("${identity.registration.worker.retry-initial-seconds}") double retryInitialSeconds,
            @Value("${identity.registration.worker.retry-max-seconds}") double retryMaxSeconds,
            @Value("${identity.registration.worker.jitter-fraction}") double jitterFraction) {
        this.jdbc = jdbc;
        this.retryInitialSeconds = retryInitialSeconds;
        this.retryMaxSeconds = retryMaxSeconds;
        this.jitterFraction = jitterFraction;
    }

    @Transactional
    public List<Lease> claim(UUID owner, int limit, int leaseSeconds) {
        if (owner == null || limit < 1 || leaseSeconds < 1) {
            throw new IllegalArgumentException("Invalid lease configuration");
        }
        return jdbc.sql("""
                WITH candidates AS (
                    SELECT id FROM registration_operations
                    WHERE status = 'PENDING' AND recovery_blocked_at IS NULL
                      AND NOT profile_ready AND next_retry_at <= clock_timestamp()
                      AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                    ORDER BY next_retry_at, id FOR UPDATE SKIP LOCKED LIMIT :limit
                )
                UPDATE registration_operations operation
                SET lease_owner = :owner, lease_until = clock_timestamp() + make_interval(secs => :seconds),
                    lease_version = operation.lease_version + 1, updated_at = clock_timestamp()
                FROM candidates WHERE operation.id = candidates.id
                RETURNING operation.id, operation.user_id, operation.lease_version
                """)
                .param("owner", owner)
                .param("limit", limit)
                .param("seconds", leaseSeconds)
                .query((row, index) -> new Lease(
                        row.getObject("id", UUID.class),
                        row.getObject("user_id", UUID.class),
                        owner,
                        row.getLong("lease_version")))
                .list();
    }

    @Transactional
    public boolean markProfileReady(Lease lease) {
        return jdbc.sql("""
                UPDATE registration_operations
                SET profile_ready = TRUE, lease_owner = NULL, lease_until = NULL, updated_at = clock_timestamp()
                WHERE id = :id AND status = 'PENDING' AND lease_owner = :owner
                  AND lease_version = :version AND lease_until > clock_timestamp()
                """)
                        .param("id", lease.operationId())
                        .param("owner", lease.owner())
                        .param("version", lease.version())
                        .update()
                == 1;
    }

    @Transactional
    public boolean recordFailure(Lease lease, boolean retryable, String errorCode) {
        if (retryInitialSeconds <= 0
                || retryMaxSeconds < retryInitialSeconds
                || jitterFraction < 0
                || jitterFraction > 1) {
            throw new IllegalArgumentException("Invalid retry configuration");
        }
        return jdbc.sql("""
                UPDATE registration_operations
                SET retry_count = retry_count + 1,
                    next_retry_at = CASE WHEN :retryable THEN clock_timestamp()
                        + make_interval(secs => LEAST(:retryMax, :retryInitial * power(2.0, LEAST(retry_count, 30))
                            * (1.0 + random() * :jitter))) ELSE next_retry_at END,
                    recovery_blocked_at = CASE WHEN :retryable THEN NULL ELSE clock_timestamp() END,
                    last_error_code = :errorCode, lease_owner = NULL, lease_until = NULL,
                    updated_at = clock_timestamp()
                WHERE id = :id AND status = 'PENDING' AND lease_owner = :owner
                  AND lease_version = :version AND lease_until > clock_timestamp()
                """)
                        .param("retryable", retryable)
                        .param("retryMax", retryMaxSeconds)
                        .param("retryInitial", retryInitialSeconds)
                        .param("jitter", jitterFraction)
                        .param("errorCode", errorCode)
                        .param("id", lease.operationId())
                        .param("owner", lease.owner())
                        .param("version", lease.version())
                        .update()
                == 1;
    }

    public record Lease(UUID operationId, UUID userId, UUID owner, long version) {}
}
