package io.github.returnaction.fitness.coins.settlement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SHA-256 от user UUID в lowercase с дефисами, отсортированных
 * лексикографически, через \n без конечного перевода строки.
 */
public final class SettlementDigest {

    private SettlementDigest() {
    }

    public static byte[] winnersDigest(List<UUID> userIds) {
        String canonical = userIds.stream()
                .map(UUID::toString)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining("\n"));
        return sha256(canonical);
    }

    public static String toHex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    public static byte[] fromHex(String digest) {
        return HexFormat.of().parseHex(digest);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
