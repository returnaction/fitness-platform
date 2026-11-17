package io.github.returnaction.fitness.challenge.outbox;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Публикует событие Challenge в Kafka. Событие считается доставленным только
 * после подтверждения send; повтор после потери ack возможен, поэтому
 * потребители обязаны дедуплицировать по event_id.
 */
@Component
@RequiredArgsConstructor
public class KafkaChallengeEventPublisher implements ChallengeEventPublisher {

    private final KafkaTemplate<String, String> kafka;

    @Override
    public void publish(UUID eventId, String eventType, UUID aggregateId, String payload) {
        String topic = "fitness." + eventType + ".v1";
        try {
            kafka.send(topic, aggregateId.toString(), payload).get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new OutboxPublishException(exception);
        }
    }
}
