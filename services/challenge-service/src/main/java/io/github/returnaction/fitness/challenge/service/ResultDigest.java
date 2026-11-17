package io.github.returnaction.fitness.challenge.service;

import io.github.returnaction.fitness.challenge.idempotency.RequestFingerprint;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SHA-256 от user UUID в lowercase с дефисами, отсортированных
 * лексикографически, через \n без конечного перевода строки.
 */
public final class ResultDigest {

    private ResultDigest() {
    }

    public static byte[] winnersDigest(List<UUID> userIds) {
        String canonical = userIds.stream()
                .map(UUID::toString)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining("\n"));
        return RequestFingerprint.sha256(canonical);
    }

    public static String toHex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
