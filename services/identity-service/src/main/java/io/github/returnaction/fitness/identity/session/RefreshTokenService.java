package io.github.returnaction.fitness.identity.session;

import io.github.returnaction.fitness.identity.registration.application.AccessTokenSigner;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ротация refresh и подпись access token в одной транзакции. */
@Service
@ConditionalOnProperty(name = "identity.jwt.enabled", havingValue = "true")
public class RefreshTokenService {
    private final JdbcClient jdbc;
    private final AccessTokenSigner signer;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(JdbcClient jdbc, AccessTokenSigner signer) {
        this.jdbc = jdbc;
        this.signer = signer;
    }

    @Transactional
    public Result refresh(String input) {
        Parsed parsed = parse(input);
        if (parsed == null) return Result.invalid();
        var location = jdbc.sql("""
                SELECT s.id AS session_id, s.user_id FROM refresh_tokens t
                JOIN auth_sessions s ON s.id=t.session_id WHERE t.id=:id
                """)
                .param("id", parsed.id())
                .query((row, n) ->
                        new Location(row.getObject("session_id", UUID.class), row.getObject("user_id", UUID.class)))
                .optional()
                .orElse(null);
        if (location == null) return Result.invalid();
        String userStatus = jdbc.sql("SELECT status FROM users WHERE id=:id FOR UPDATE")
                .param("id", location.userId())
                .query(String.class)
                .optional()
                .orElse(null);
        if (userStatus == null) return Result.invalid();
        var session = jdbc.sql("""
                SELECT expires_at, revoked_at, clock_timestamp() AS checked_at FROM auth_sessions
                WHERE id=:id AND user_id=:user FOR UPDATE
                """)
                .param("id", location.sessionId())
                .param("user", location.userId())
                .query((row, n) -> new Session(
                        time(row.getObject("expires_at", OffsetDateTime.class)),
                        time(row.getObject("revoked_at", OffsetDateTime.class)),
                        time(row.getObject("checked_at", OffsetDateTime.class))))
                .optional()
                .orElse(null);
        if (session == null) return Result.invalid();
        var token = jdbc.sql("""
                SELECT secret_hash, used_at, revoked_at, expires_at FROM refresh_tokens
                WHERE id=:id AND session_id=:session FOR UPDATE
                """)
                .param("id", parsed.id())
                .param("session", location.sessionId())
                .query((row, n) -> new Token(
                        row.getBytes("secret_hash"),
                        time(row.getObject("used_at", OffsetDateTime.class)),
                        time(row.getObject("revoked_at", OffsetDateTime.class)),
                        time(row.getObject("expires_at", OffsetDateTime.class))))
                .optional()
                .orElse(null);
        if (token == null || !MessageDigest.isEqual(token.hash(), sha256(parsed.secret()))) return Result.invalid();
        if (token.usedAt() != null) {
            jdbc.sql("UPDATE auth_sessions SET revoked_at=clock_timestamp() WHERE id=:id AND revoked_at IS NULL")
                    .param("id", location.sessionId())
                    .update();
            return Result.invalid();
        }
        if (session.revokedAt() != null
                || token.revokedAt() != null
                || !session.expiresAt().isAfter(session.checkedAt())
                || !token.expiresAt().isAfter(session.checkedAt())) {
            return Result.invalid();
        }
        if (!"ACTIVE".equals(userStatus)) return Result.unavailableAccount();
        UUID newId = UUID.randomUUID();
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String access;
        try {
            access = signer.sign(location.userId(), location.sessionId(), session.expiresAt());
        } catch (IllegalArgumentException expired) {
            return Result.invalid();
        }
        jdbc.sql("""
                INSERT INTO refresh_tokens (id, session_id, secret_hash, expires_at)
                VALUES (:id, :session, :hash, :expires)
                """)
                .param("id", newId)
                .param("session", location.sessionId())
                .param("hash", sha256(secret))
                .param("expires", OffsetDateTime.ofInstant(session.expiresAt(), java.time.ZoneOffset.UTC))
                .update();
        jdbc.sql("UPDATE refresh_tokens SET used_at=clock_timestamp(), replaced_by_token_id=:replacement WHERE id=:id")
                .param("replacement", newId)
                .param("id", parsed.id())
                .update();
        String refresh = newId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        long remaining = java.time.Duration.between(session.checkedAt(), session.expiresAt())
                .getSeconds();
        return Result.success(access, refresh, Math.max(0, remaining));
    }

    private static Parsed parse(String value) {
        if (value == null || value.length() != 80 || value.charAt(36) != '.') return null;
        try {
            String idPart = value.substring(0, 36);
            UUID id = UUID.fromString(idPart);
            if (!id.toString().equals(idPart)) return null;
            String encoded = value.substring(37);
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length != 32
                    || !Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(bytes)
                            .equals(encoded)) return null;
            return new Parsed(id, bytes);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static Instant time(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record Parsed(UUID id, byte[] secret) {}

    private record Location(UUID sessionId, UUID userId) {}

    private record Session(Instant expiresAt, Instant revokedAt, Instant checkedAt) {}

    private record Token(byte[] hash, Instant usedAt, Instant revokedAt, Instant expiresAt) {}

    public static final class Result {
        private final String code;
        private final String access;
        private final String refresh;
        private final long remaining;

        private Result(String code, String access, String refresh, long remaining) {
            this.code = code;
            this.access = access;
            this.refresh = refresh;
            this.remaining = remaining;
        }

        static Result invalid() {
            return new Result("invalid_refresh_token", null, null, 0);
        }

        static Result unavailableAccount() {
            return new Result("account_unavailable", null, null, 0);
        }

        static Result success(String access, String refresh, long remaining) {
            return new Result(null, access, refresh, remaining);
        }

        public String code() {
            return code;
        }

        public String access() {
            return access;
        }

        public String refresh() {
            return refresh;
        }

        public long remaining() {
            return remaining;
        }
    }
}
