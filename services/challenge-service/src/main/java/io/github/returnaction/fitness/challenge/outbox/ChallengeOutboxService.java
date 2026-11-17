package io.github.returnaction.fitness.challenge.outbox;

import io.github.returnaction.fitness.challenge.repository.ChallengeOutboxRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Добавляет событие в outbox. MANDATORY гарантирует, что событие и локальное
 * изменение фиксируются одной транзакцией.
 */
@Service
@RequiredArgsConstructor
public class ChallengeOutboxService {

    private final ChallengeOutboxRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID eventId, String eventType, int schemaVersion, UUID aggregateId, String payload) {
        repository.insert(eventId, eventType, schemaVersion, aggregateId, payload);
    }
}
