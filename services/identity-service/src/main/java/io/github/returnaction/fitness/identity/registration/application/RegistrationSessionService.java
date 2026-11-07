package io.github.returnaction.fitness.identity.registration.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Создаёт сессию только для завершённой регистрации с проверенным паролем. */
@Service
public class RegistrationSessionService {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public RegistrationSessionService(JdbcClient jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public IssuedSession create(UUID operationId, String password) {
        var row = jdbc.sql("""
                SELECT u.id, u.status AS user_status, o.status AS operation_status, c.password_hash
                FROM registration_operations o
                JOIN users u ON u.id = o.user_id
                JOIN user_credentials c ON c.user_id = u.id
                WHERE o.id = :operationId
                FOR UPDATE OF u, o
                """)
                .param("operationId", operationId)
                .query((rs, index) -> new Credentials(
                        rs.getObject("id", UUID.class), rs.getString("user_status"),
                        rs.getString("operation_status"), rs.getString("password_hash")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Registration is unavailable"));
        if (password == null || !passwordEncoder.matches(password, row.passwordHash())) {
            throw new RegistrationConflictException("idempotency_key_conflict");
        }
        if (!"ACTIVE".equals(row.userStatus()) || !"COMPLETED".equals(row.operationStatus())) {
            throw new RegistrationNotReadyException();
        }
        jdbc.sql("""
                UPDATE auth_sessions SET revoked_at = clock_timestamp()
                WHERE registration_operation_id = :operationId AND revoked_at IS NULL
                """).param("operationId", operationId).update();
        return issueSession(row.userId(), operationId);
    }

    @Transactional
    public IssuedSession createLogin(UUID userId, String expectedHash, String upgradedHash) {
        boolean available =
                jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM users WHERE id=:id AND status='ACTIVE' FOR UPDATE)
                """).param("id", userId).query(Boolean.class).single();
        if (!available) throw new LoginCredentialsChangedException();
        var currentHash = jdbc.sql("SELECT password_hash FROM user_credentials WHERE user_id=:id FOR UPDATE")
                .param("id", userId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (currentHash == null || !currentHash.equals(expectedHash)) throw new LoginCredentialsChangedException();
        if (upgradedHash != null) {
            jdbc.sql("UPDATE user_credentials SET password_hash=:hash, updated_at=clock_timestamp() WHERE user_id=:id")
                    .param("id", userId)
                    .param("hash", upgradedHash)
                    .update();
        }
        return issueSession(userId, null);
    }

    private IssuedSession issueSession(UUID userId, UUID operationId) {
        UUID sessionId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant expiresAt = jdbc.sql("""
                INSERT INTO auth_sessions (id, user_id, registration_operation_id, expires_at)
                VALUES (:id, :userId, :operationId, CURRENT_TIMESTAMP + INTERVAL '30 days')
                RETURNING expires_at
                """)
                .param("id", sessionId)
                .param("userId", userId)
                .param("operationId", operationId)
                .query((rs, index) ->
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant())
                .single();
        jdbc.sql("""
                INSERT INTO refresh_tokens (id, session_id, secret_hash, expires_at)
                SELECT :id, id, :hash, expires_at FROM auth_sessions WHERE id = :sessionId
                """)
                .param("id", tokenId)
                .param("hash", sha256(secret))
                .param("sessionId", sessionId)
                .update();
        return new IssuedSession(userId, sessionId, expiresAt, tokenId + "." + encodedSecret);
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Credentials(UUID userId, String userStatus, String operationStatus, String passwordHash) {}

    public static final class IssuedSession {
        private final UUID sessionId;
        private final UUID userId;
        private final Instant expiresAt;
        private final String refreshToken;

        IssuedSession(UUID userId, UUID sessionId, Instant expiresAt, String refreshToken) {
            this.userId = userId;
            this.sessionId = sessionId;
            this.expiresAt = expiresAt;
            this.refreshToken = refreshToken;
        }

        public UUID sessionId() {
            return sessionId;
        }

        public UUID userId() {
            return userId;
        }

        public Instant expiresAt() {
            return expiresAt;
        }

        public String refreshToken() {
            return refreshToken;
        }
    }
}
