package io.github.returnaction.fitness.challenge.settlement;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Подписка на `fitness.coins.settlement_completed.v1`; key — `challengeId`.
 * Ошибки применения пробрасываются, чтобы Kafka повторила доставку.
 */
@Component
@RequiredArgsConstructor
public class SettlementCompletedConsumer {

    private final SettlementCompletedProcessor processor;

    @KafkaListener(
            topics = "${challenge.settlement.topic}",
            groupId = "${challenge.settlement.consumer-group}")
    public void onMessage(ConsumerRecord<String, String> record) {
        processor.apply(record.key(), record.value());
    }
}
