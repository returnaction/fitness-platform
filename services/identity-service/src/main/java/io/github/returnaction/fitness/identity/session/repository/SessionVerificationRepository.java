package io.github.returnaction.fitness.identity.session.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Читает актуальную сессию из собственной базы Identity без кеширования. */
@Repository
public class SessionVerificationRepository {

    private final EntityManager entityManager;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring injects a transaction-scoped EntityManager proxy")
    public SessionVerificationRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public Result check(UUID userId, UUID sessionId) {
        Object[] row = (Object[]) entityManager
                .createNativeQuery("""
                        WITH checked AS MATERIALIZED (SELECT clock_timestamp() AS at)
                        SELECT checked.at, EXISTS (
                            SELECT 1 FROM auth_sessions s JOIN users u ON u.id = s.user_id
                            WHERE s.id = :sessionId AND s.user_id = :userId
                              AND u.status = 'ACTIVE' AND s.revoked_at IS NULL AND s.expires_at > checked.at
                        ) AS active FROM checked
                        """)
                .setParameter("sessionId", sessionId)
                .setParameter("userId", userId)
                .getSingleResult();
        return new Result((Boolean) row[1], (Instant) row[0]);
    }

    public record Result(boolean active, Instant checkedAt) {
    }
}
