package io.github.returnaction.fitness.identity.registration.application;

import io.github.returnaction.fitness.identity.registration.dto.RegisterUserRequest;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Проверяет учётные данные и ускоряет только безопасную незаблокированную операцию. */
@Service
public final class PendingLoginService {
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final RegistrationInputValidator validator;
    private final String dummyHash;

    public PendingLoginService(JdbcClient jdbc, PasswordEncoder encoder, RegistrationInputValidator validator) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.validator = validator;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public Outcome login(RegisterUserRequest request) {
        var input = validator.validate(request);
        var user = jdbc.sql("""
                SELECT u.id, u.status, c.password_hash FROM users u
                JOIN user_credentials c ON c.user_id = u.id WHERE u.email_canonical = :email
                """)
                .param("email", input.emailCanonical())
                .query((row, index) -> new User(
                        row.getObject("id", UUID.class), row.getString("status"), row.getString("password_hash")))
                .optional()
                .orElse(null);
        String hash = user == null ? dummyHash : user.passwordHash();
        boolean matches = encoder.matches(input.password(), hash);
        if (user == null || !matches || (!"PENDING".equals(user.status()) && !"ACTIVE".equals(user.status()))) {
            return Outcome.INVALID;
        }
        if ("ACTIVE".equals(user.status())) return Outcome.ACTIVE_LOGIN_UNAVAILABLE;
        jdbc.sql("""
                UPDATE registration_operations o
                SET next_retry_at = clock_timestamp(), version = version + 1, updated_at = clock_timestamp()
                FROM users u, user_credentials c
                WHERE o.user_id = :user AND u.id = o.user_id AND u.status = 'PENDING'
                  AND c.user_id = u.id AND c.password_hash = :hash
                  AND o.status = 'PENDING' AND o.recovery_blocked_at IS NULL
                  AND (o.lease_until IS NULL OR o.lease_until <= clock_timestamp())
                  AND o.next_retry_at > clock_timestamp()
                """).param("user", user.id()).param("hash", hash).update();
        return Outcome.PENDING;
    }

    private record User(UUID id, String status, String passwordHash) {}

    public enum Outcome {
        INVALID,
        PENDING,
        ACTIVE_LOGIN_UNAVAILABLE
    }
}
