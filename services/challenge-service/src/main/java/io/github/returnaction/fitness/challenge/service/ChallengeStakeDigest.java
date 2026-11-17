package io.github.returnaction.fitness.challenge.service;

import io.github.returnaction.fitness.challenge.idempotency.RequestFingerprint;
import io.github.returnaction.fitness.challenge.repository.ChallengeSealRepository;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Канонический digest набора: hold_id,user_id,amount по возрастанию строкового
 * hold_id, через \n без конечного перевода.
 */
public final class ChallengeStakeDigest {

    private ChallengeStakeDigest() {
    }

    public static byte[] digest(List<ChallengeSealRepository.SealItem> items) {
        String canonical = items.stream()
                .sorted(Comparator.comparing(item -> item.holdId().toString()))
                .map(item -> item.holdId() + "," + item.userId() + "," + item.amount())
                .collect(Collectors.joining("\n"));
        return RequestFingerprint.sha256(canonical);
    }

    public static String toHex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
