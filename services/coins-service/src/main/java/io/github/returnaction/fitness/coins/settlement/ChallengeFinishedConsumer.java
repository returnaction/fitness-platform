package io.github.returnaction.fitness.coins.settlement;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Подписка на `fitness.challenge.finished.v1`; key — `challengeId`.
 * Временная ошибка чтения победителей пробрасывается для повтора Kafka.
 */
@Component
@RequiredArgsConstructor
public class ChallengeFinishedConsumer {

    private final ChallengeFinishedProcessor processor;

    @KafkaListener(
            topics = "${coins.settlement.topic}",
            groupId = "${coins.settlement.consumer-group}")
    public void onMessage(ConsumerRecord<String, String> record) {
        processor.handle(record.key(), record.value());
    }
}
