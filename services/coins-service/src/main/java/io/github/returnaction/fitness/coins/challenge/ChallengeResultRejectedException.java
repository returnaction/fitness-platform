package io.github.returnaction.fitness.coins.challenge;

import lombok.Getter;

/**
 * Постоянный отказ Challenge или некорректный ответ; повтор не поможет.
 */
@Getter
public final class ChallengeResultRejectedException extends RuntimeException {

    private final String reason;

    public ChallengeResultRejectedException(String reason) {
        super("Challenge result request was rejected.");
        this.reason = reason;
    }

    public ChallengeResultRejectedException(String reason, Throwable cause) {
        super("Challenge result request was rejected.", cause);
        this.reason = reason;
    }

}
