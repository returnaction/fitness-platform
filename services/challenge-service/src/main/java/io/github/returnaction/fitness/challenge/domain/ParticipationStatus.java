package io.github.returnaction.fitness.challenge.domain;

/**
 * Состояние участия в challenge.
 *
 * <p>Участник становится {@code JOINED} после подтверждения места, переходит в
 * {@code ACTIVE} при старте challenge, а затем в {@code WINNER} или
 * {@code COMPLETED} при фиксации результата. Отмена challenge не меняет статус
 * участия: она видна в статусе challenge и {@code refundStatus}.</p>
 */
public enum ParticipationStatus {
    JOINED,
    ACTIVE,
    WINNER,
    COMPLETED;

    public boolean canTransitionTo(ParticipationStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case JOINED -> target == ACTIVE;
            case ACTIVE -> target == WINNER || target == COMPLETED;
            case WINNER, COMPLETED -> false;
        };
    }

    public void requireTransitionTo(ParticipationStatus target) {
        if (!canTransitionTo(target)) {
            throw new InvalidStateTransitionException(this, target);
        }
    }

    public boolean isTerminal() {
        return this == WINNER || this == COMPLETED;
    }
}
