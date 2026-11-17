package io.github.returnaction.fitness.challenge.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Отпечаток тела запроса: SHA-256 от исходных UTF-8 байтов.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static byte[] sha256(byte[] body) {
        try {
            byte[] payload = body == null ? new byte[0] : body;
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public static byte[] sha256(String body) {
        return sha256(body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }
}
