package io.github.returnaction.fitness.challenge.domain;

/**
 * Состояние жизненного цикла challenge в MVP.
 *
 * <p>{@code AWAITING_RESULTS} зарезервирован для будущих сценариев и в MVP не
 * выставляется: переходы в него и из него запрещены. {@code FINISHED} и
 * {@code CANCELLED} окончательны.</p>
 */
public enum ChallengeStatus {
    DRAFT,
    OPEN,
    ACTIVE,
    AWAITING_RESULTS,
    FINISHED,
    CANCELLED;

    public boolean canTransitionTo(ChallengeStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case DRAFT -> target == OPEN;
            case OPEN -> target == ACTIVE || target == CANCELLED;
            case ACTIVE -> target == FINISHED;
            case AWAITING_RESULTS, FINISHED, CANCELLED -> false;
        };
    }

    public void requireTransitionTo(ChallengeStatus target) {
        if (!canTransitionTo(target)) {
            throw new InvalidStateTransitionException(this, target);
        }
    }

    public boolean isTerminal() {
        return this == FINISHED || this == CANCELLED;
    }
}
