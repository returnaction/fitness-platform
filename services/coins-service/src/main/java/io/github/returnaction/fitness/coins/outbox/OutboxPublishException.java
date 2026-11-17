package io.github.returnaction.fitness.coins.outbox;

public final class OutboxPublishException extends RuntimeException {

    public OutboxPublishException(Throwable cause) {
        super("Could not publish outbox event.", cause);
    }
}
