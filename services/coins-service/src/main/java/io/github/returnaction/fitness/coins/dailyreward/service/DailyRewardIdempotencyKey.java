package io.github.returnaction.fitness.coins.dailyreward.service;

import io.github.returnaction.fitness.coins.dailyreward.exception.InvalidIdempotencyKeyException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Канонизирует Idempotency-Key и возвращает его SHA-256 digest.
 */
final class DailyRewardIdempotencyKey {

    private static final String ALGORITHM = "SHA-256";

    private DailyRewardIdempotencyKey() {
    }

    static byte[] digest(String rawKey) {
        UUID key = canonical(rawKey);
        try {
            return MessageDigest.getInstance(ALGORITHM)
                .digest(key.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(ALGORITHM + " is unavailable", exception);
        }
    }

    private static UUID canonical(String rawKey) {
        if (rawKey == null) {
            throw new InvalidIdempotencyKeyException();
        }
        UUID key;
        try {
            key = UUID.fromString(rawKey);
        } catch (IllegalArgumentException exception) {
            throw new InvalidIdempotencyKeyException();
        }
        if (!key.toString().equalsIgnoreCase(rawKey)) {
            throw new InvalidIdempotencyKeyException();
        }
        return key;
    }
}
