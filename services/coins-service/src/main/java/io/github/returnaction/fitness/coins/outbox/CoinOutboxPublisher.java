package io.github.returnaction.fitness.coins.outbox;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Публикует outbox с lease. Событие помечается published только после ack;
 * потерянный ack может привести к повторной публикации, которую потребитель
 * дедуплицирует по event_id.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoinOutboxPublisher {

    private final CoinOutboxRepository repository;
    private final CoinEventPublisher publisher;

    @Value("${coins.outbox.lease:30s}")
    private Duration lease;

    @Value("${coins.outbox.batch:20}")
    private int batch;

    @Scheduled(fixedDelayString = "${coins.outbox.fixed-delay:2s}")
    public void publish() {
        int leaseSeconds = Math.toIntExact(lease.toSeconds());
        for (var row : repository.claim(batch, leaseSeconds)) {
            try {
                publisher.publish(row.eventId(), row.eventType(), row.aggregateId(), row.payload());
                repository.markPublished(row.eventId());
            } catch (RuntimeException exception) {
                repository.clearLease(row.eventId());
                log.warn("Outbox publish failed, event_id={}, event_type={}",
                        row.eventId(), row.eventType());
            }
        }
    }
}
