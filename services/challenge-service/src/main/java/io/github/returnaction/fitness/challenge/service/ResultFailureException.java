package io.github.returnaction.fitness.challenge.service;

/**
 * Ошибка фиксации или чтения результата с машинной причиной для gRPC.
 */
public final class ResultFailureException extends RuntimeException {

    private final Reason reason;

    public ResultFailureException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        INVALID_REQUEST,
        RESULT_ID_CONFLICT,
        CHALLENGE_RESULT_EXISTS,
        RESULT_NOT_FOUND,
        UNSUPPORTED_OUTCOME,
        CHALLENGE_NOT_ACTIVE,
        STAKE_SET_NOT_SEALED,
        STAKE_SET_MISMATCH,
        WINNER_COUNT_MISMATCH,
        WINNER_NOT_PARTICIPANT
    }
}
