package io.github.returnaction.fitness.challenge.outbox;

public final class OutboxPublishException extends RuntimeException {

    public OutboxPublishException(Throwable cause) {
        super("Could not publish outbox event.", cause);
    }
}
