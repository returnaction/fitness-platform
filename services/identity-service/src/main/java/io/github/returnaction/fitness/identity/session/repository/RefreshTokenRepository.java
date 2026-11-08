package io.github.returnaction.fitness.identity.session.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class RefreshTokenRepository {
    private final EntityManager entityManager;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring injects a transaction-scoped EntityManager proxy")
    public RefreshTokenRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Location findLocation(UUID id) {
        var rows = entityManager.createNativeQuery("""
                SELECT s.id, s.user_id FROM refresh_tokens t
                JOIN auth_sessions s ON s.id=t.session_id WHERE t.id=:id
                """).setParameter("id", id).getResultList();
        if (rows.isEmpty())
            return null;
        Object[] row = (Object[]) rows.getFirst();
        return new Location((UUID) row[0], (UUID) row[1]);
    }

    public String lockUser(UUID id) {
        var rows = entityManager
                .createNativeQuery("SELECT status FROM users WHERE id=:id FOR UPDATE")
                .setParameter("id", id)
                .getResultList();
        return rows.isEmpty() ? null : (String) rows.getFirst();
    }

    public Session lockSession(UUID id, UUID userId) {
        var rows = entityManager
                .createNativeQuery("""
                        SELECT expires_at, revoked_at, clock_timestamp() FROM auth_sessions
                        WHERE id=:id AND user_id=:user FOR UPDATE
                        """)
                .setParameter("id", id)
                .setParameter("user", userId)
                .getResultList();
        if (rows.isEmpty())
            return null;
        Object[] row = (Object[]) rows.getFirst();
        return new Session((Instant) row[0], (Instant) row[1], (Instant) row[2]);
    }

    public Token lockToken(UUID id, UUID sessionId) {
        var rows = entityManager
                .createNativeQuery("""
                        SELECT secret_hash, used_at, revoked_at, expires_at FROM refresh_tokens
                        WHERE id=:id AND session_id=:session FOR UPDATE
                        """)
                .setParameter("id", id)
                .setParameter("session", sessionId)
                .getResultList();
        if (rows.isEmpty())
            return null;
        Object[] row = (Object[]) rows.getFirst();
        return new Token((byte[]) row[0], (Instant) row[1], (Instant) row[2], (Instant) row[3]);
    }

    public void revokeSession(UUID id) {
        entityManager
                .createNativeQuery(
                        "UPDATE auth_sessions SET revoked_at=clock_timestamp() WHERE id=:id AND revoked_at IS NULL")
                .setParameter("id", id)
                .executeUpdate();
    }

    public void revokeAllSessions(UUID userId) {
        entityManager.createNativeQuery("UPDATE auth_sessions SET revoked_at=clock_timestamp() "
                + "WHERE user_id=:userId AND revoked_at IS NULL AND expires_at > clock_timestamp()")
                .setParameter("userId", userId)
                .executeUpdate();
    }

    public void insertToken(UUID id, UUID sessionId, byte[] hash, Instant expires) {
        entityManager
                .createNativeQuery("""
                        INSERT INTO refresh_tokens (id, session_id, secret_hash, expires_at)
                        VALUES (:id, :session, :hash, :expires)
                        """)
                .setParameter("id", id)
                .setParameter("session", sessionId)
                .setParameter("hash", hash)
                .setParameter("expires", expires)
                .executeUpdate();
    }

    public void markUsed(UUID id, UUID replacement) {
        entityManager
                .createNativeQuery(
                        "UPDATE refresh_tokens SET used_at=clock_timestamp(), replaced_by_token_id=:replacement WHERE id=:id")
                .setParameter("replacement", replacement)
                .setParameter("id", id)
                .executeUpdate();
    }

    public record Location(UUID sessionId, UUID userId) {
    }

    public record Session(Instant expiresAt, Instant revokedAt, Instant checkedAt) {
    }

    public record Token(byte[] hash, Instant usedAt, Instant revokedAt, Instant expiresAt) {
        public Token {
            hash = hash == null ? null : hash.clone();
        }

        @Override
        public byte[] hash() {
            return hash == null ? null : hash.clone();
        }

        @Override
        public String toString() {
            return "Token[redacted]";
        }
    }
}
