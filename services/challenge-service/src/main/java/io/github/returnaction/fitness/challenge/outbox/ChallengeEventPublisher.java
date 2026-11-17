package io.github.returnaction.fitness.challenge.outbox;

import java.util.UUID;

public interface ChallengeEventPublisher {

    void publish(UUID eventId, String eventType, UUID aggregateId, String payload);
}
