package io.github.returnaction.fitness.coins.challenge;

/**
 * Challenge временно недоступен; расчёт нельзя считать завершённым.
 */
public final class ChallengeResultUnavailableException extends RuntimeException {

    public ChallengeResultUnavailableException(Throwable cause) {
        super("Challenge result service is temporarily unavailable.", cause);
    }
}
