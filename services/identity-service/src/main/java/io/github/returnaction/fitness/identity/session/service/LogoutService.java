package io.github.returnaction.fitness.identity.session.service;

import io.github.returnaction.fitness.identity.shared.exception.InvalidAccessTokenException;
import io.github.returnaction.fitness.identity.session.repository.RefreshTokenRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class LogoutService {

    private final RefreshTokenRepository repository;
    private final TransactionTemplate transactions;

    public void logout(String token) {
        if (token.length() != 80 || token.charAt(36) != '.')
            return;
        UUID tokenId;
        byte[] secret;
        try {
            String id = token.substring(0, 36);
            tokenId = UUID.fromString(id);
            String encoded = token.substring(37);
            secret = Base64.getUrlDecoder().decode(encoded);
            if (!tokenId.toString().equals(id) || secret.length != 32
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(secret).equals(encoded))
                return;
        } catch (IllegalArgumentException invalid) {
            return;
        }
        var location = repository.findSessionAndUserIds(tokenId);
        if (location == null)
            return;
        transactions.executeWithoutResult(status -> {
            if (repository.lockUser(location.userId()) == null)
                return;
            if (repository.lockSession(location.sessionId(), location.userId()) == null)
                return;
            var saved = repository.lockToken(tokenId, location.sessionId());
            if (saved != null && MessageDigest.isEqual(saved.hash(), hash(secret)))
                repository.revokeSession(location.sessionId());
        });
    }

    public void logoutAll(UUID userId) {
        transactions.executeWithoutResult(status -> {
            if (repository.lockUser(userId) == null)
                throw new InvalidAccessTokenException();
            repository.revokeAllSessions(userId);
        });
    }

    private static byte[] hash(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
