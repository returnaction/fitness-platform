package io.github.returnaction.fitness.challenge.idempotency;

import io.github.returnaction.fitness.challenge.shared.exception.BusinessConflictException;
import io.github.returnaction.fitness.challenge.shared.exception.InvalidRequestException;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Разрешает повтор HTTP-запроса по Idempotency-Key: тот же отпечаток тела
 * возвращает сохранённый ответ, другой отпечаток даёт 409.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyStore store;

    public UUID requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidRequestException("Idempotency-Key is required.");
        }
        try {
            return UUID.fromString(idempotencyKey);
        } catch (IllegalArgumentException _) {
            throw new InvalidRequestException("Idempotency-Key must be a UUID.");
        }
    }

    public Optional<StoredHttpResponse> findReplay(UUID userId, String method, String path, UUID key, byte[] body) {
        return store.find(userId, method, path, key)
                .map(stored -> {
                    if (!MessageDigest.isEqual(stored.requestFingerprint(), RequestFingerprint.sha256(body))) {
                        throw new BusinessConflictException("idempotency_key_conflict",
                                "Idempotency-Key was used with a different request.");
                    }
                    return stored.response();
                });
    }

    public void store(UUID userId, String method, String path, UUID key, byte[] body,
                      StoredHttpResponse response) {
        store.insert(userId, method, path, key, RequestFingerprint.sha256(body), response);
    }
}
