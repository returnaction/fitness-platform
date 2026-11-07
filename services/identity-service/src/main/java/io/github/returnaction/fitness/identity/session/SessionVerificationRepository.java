package io.github.returnaction.fitness.identity.session;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Читает актуальную сессию из собственной базы Identity без кеширования. */
@Repository
public class SessionVerificationRepository {
    private final JdbcClient jdbc;

    public SessionVerificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Result check(UUID userId, UUID sessionId) {
        return jdbc.sql("""
                WITH checked AS MATERIALIZED (SELECT clock_timestamp() AS at)
                SELECT checked.at, EXISTS (
                    SELECT 1 FROM auth_sessions s JOIN users u ON u.id = s.user_id
                    WHERE s.id = :sessionId AND s.user_id = :userId
                      AND u.status = 'ACTIVE' AND s.revoked_at IS NULL AND s.expires_at > checked.at
                ) AS active FROM checked
                """)
                .param("sessionId", sessionId)
                .param("userId", userId)
                .query((row, index) -> new Result(
                        row.getBoolean("active"),
                        row.getObject("at", OffsetDateTime.class).toInstant()))
                .single();
    }

    public record Result(boolean active, Instant checkedAt) {}
}
