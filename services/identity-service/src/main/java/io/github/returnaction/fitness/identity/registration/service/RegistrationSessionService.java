package io.github.returnaction.fitness.identity.registration.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.returnaction.fitness.identity.registration.exception.RegistrationConflictException;
import io.github.returnaction.fitness.identity.registration.exception.RegistrationNotReadyException;
import io.github.returnaction.fitness.identity.registration.repository.RegistrationSessionRepository;
import io.github.returnaction.fitness.identity.shared.exception.LoginCredentialsChangedException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Создаёт сессию только для завершённой регистрации с проверенным паролем. */
@Service
public class RegistrationSessionService {
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring-managed repository is shared and not exposed.")
    private final RegistrationSessionRepository repository;

    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public RegistrationSessionService(RegistrationSessionRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public IssuedSession create(UUID operationId, String password) {
        var row = repository.lockRegistration(operationId);
        if (password == null || !passwordEncoder.matches(password, row.passwordHash())) {
            throw new RegistrationConflictException("idempotency_key_conflict");
        }
        if (!"ACTIVE".equals(row.userStatus()) || !"COMPLETED".equals(row.operationStatus())) {
            throw new RegistrationNotReadyException();
        }
        repository.revokeRegistrationSessions(operationId);
        return issueSession(row.userId(), operationId);
    }

    @Transactional
    public IssuedSession createLogin(UUID userId, String expectedHash, String upgradedHash) {
        boolean available = repository.lockActiveUser(userId);
        if (!available)
            throw new LoginCredentialsChangedException();
        var currentHash = repository.lockPasswordHash(userId);
        if (currentHash == null || !currentHash.equals(expectedHash))
            throw new LoginCredentialsChangedException();
        if (upgradedHash != null) {
            repository.updatePasswordHash(userId, upgradedHash);
        }
        return issueSession(userId, null);
    }

    private IssuedSession issueSession(UUID userId, UUID operationId) {
        UUID sessionId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant expiresAt = repository.insertSession(sessionId, userId, operationId);
        repository.insertRefresh(tokenId, sessionId, sha256(secret));
        return new IssuedSession(userId, sessionId, expiresAt, tokenId + "." + encodedSecret);
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

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
