package io.github.returnaction.fitness.coins.stake;

import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Канонические digest набора: строки hold_id,user_id,amount, отсортированные
 * по строковому hold_id, через \n без конечного перевода строки.
 */
public final class StakeDigest {

    private StakeDigest() {
    }

    public static byte[] digest(List<SealedStake> stakes) {
        String canonical = stakes.stream()
                .sorted(Comparator.comparing(stake -> stake.holdId().toString()))
                .map(stake -> stake.holdId() + "," + stake.userId() + "," + stake.amount())
                .collect(Collectors.joining("\n"));
        return RequestHash.sha256(canonical);
    }

    public static String toHex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    public static byte[] fromHex(String digestHex) {
        if (digestHex == null || !digestHex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("digest must be 64 lowercase hex characters");
        }
        return HexFormat.of().parseHex(digestHex);
    }
}
