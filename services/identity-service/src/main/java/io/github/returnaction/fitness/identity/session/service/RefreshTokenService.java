package io.github.returnaction.fitness.identity.session.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.returnaction.fitness.identity.security.jwt.AccessTokenSigner;
import io.github.returnaction.fitness.identity.session.repository.RefreshTokenRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ротация refresh и подпись access token в одной транзакции. */
@Service
@ConditionalOnProperty(name = "identity.jwt.enabled", havingValue = "true")
public class RefreshTokenService {
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring-managed repository is shared and not exposed.")
    private final RefreshTokenRepository repository;

    private final AccessTokenSigner signer;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, AccessTokenSigner signer) {
        this.repository = repository;
        this.signer = signer;
    }

    @Transactional
    public Result refresh(String input) {
        Parsed parsed = parse(input);
        if (parsed == null)
            return Result.invalid();
        var location = repository.findLocation(parsed.id());
        if (location == null)
            return Result.invalid();
        String userStatus = repository.lockUser(location.userId());
        if (userStatus == null)
            return Result.invalid();
        var session = repository.lockSession(location.sessionId(), location.userId());
        if (session == null)
            return Result.invalid();
        var token = repository.lockToken(parsed.id(), location.sessionId());
        if (token == null || !MessageDigest.isEqual(token.hash(), sha256(parsed.secret())))
            return Result.invalid();
        if (token.usedAt() != null) {
            repository.revokeSession(location.sessionId());
            return Result.invalid();
        }
        if (session.revokedAt() != null
                || token.revokedAt() != null
                || !session.expiresAt().isAfter(session.checkedAt())
                || !token.expiresAt().isAfter(session.checkedAt())) {
            return Result.invalid();
        }
        if (!"ACTIVE".equals(userStatus))
            return Result.unavailableAccount();
        UUID newId = UUID.randomUUID();
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String access;
        try {
            access = signer.sign(location.userId(), location.sessionId(), session.expiresAt());
        } catch (IllegalArgumentException expired) {
            return Result.invalid();
        }
        repository.insertToken(newId, location.sessionId(), sha256(secret), session.expiresAt());
        repository.markUsed(parsed.id(), newId);
        String refresh = newId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        long remaining = java.time.Duration.between(session.checkedAt(), session.expiresAt())
                .getSeconds();
        return Result.success(access, refresh, Math.max(0, remaining));
    }

    private static Parsed parse(String value) {
        if (value == null || value.length() != 80 || value.charAt(36) != '.')
            return null;
        try {
            String idPart = value.substring(0, 36);
            UUID id = UUID.fromString(idPart);
            if (!id.toString().equals(idPart))
                return null;
            String encoded = value.substring(37);
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length != 32
                    || !Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(bytes)
                            .equals(encoded))
                return null;
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

    private record Parsed(UUID id, byte[] secret) {
    }

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
