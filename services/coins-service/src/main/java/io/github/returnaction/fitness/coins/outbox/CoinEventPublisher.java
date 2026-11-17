package io.github.returnaction.fitness.coins.outbox;

import java.util.UUID;

public interface CoinEventPublisher {

    void publish(UUID eventId, String eventType, UUID aggregateId, String payload);
}
