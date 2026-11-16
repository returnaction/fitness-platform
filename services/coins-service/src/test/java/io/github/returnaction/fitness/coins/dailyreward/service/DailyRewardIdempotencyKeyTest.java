package io.github.returnaction.fitness.coins.dailyreward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.coins.dailyreward.exception.InvalidIdempotencyKeyException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DailyRewardIdempotencyKeyTest {

    @Test
    void rejectsMissingMalformedAndNonCanonicalKeys() {
        assertThatThrownBy(() -> DailyRewardIdempotencyKey.digest(null))
            .isInstanceOf(InvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> DailyRewardIdempotencyKey.digest("not-a-uuid"))
            .isInstanceOf(InvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> DailyRewardIdempotencyKey.digest("{" + UUID.randomUUID() + "}"))
            .isInstanceOf(InvalidIdempotencyKeyException.class);
    }

    @Test
    void canonicalizesCaseAndReturnsSha256Digest() {
        UUID key = UUID.randomUUID();

        byte[] lowercase = DailyRewardIdempotencyKey.digest(key.toString());
        byte[] uppercase = DailyRewardIdempotencyKey.digest(key.toString().toUpperCase());

        assertThat(lowercase).hasSize(32).containsExactly(uppercase);
    }
}
