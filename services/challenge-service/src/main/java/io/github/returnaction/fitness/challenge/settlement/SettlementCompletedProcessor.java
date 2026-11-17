package io.github.returnaction.fitness.challenge.settlement;

import io.github.returnaction.fitness.challenge.idempotency.RequestFingerprint;
import io.github.returnaction.fitness.challenge.repository.ChallengeInboxRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeResultRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeSettlementStatusRepository;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Применяет `coins.settlement_completed` через inbox: проекция `COMPLETED`
 * и запись inbox фиксируются одной локальной транзакцией. Конфликт digest
 * и несовпадение с окончательным результатом не изменяют состояние.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementCompletedProcessor {

    static final String CONSUMER = "challenge-service.settlement-completed";
    private static final String EVENT_TYPE = "coins.settlement_completed";
    private static final String PRODUCER = "coins";
    private static final int SCHEMA_VERSION = 1;
    private static final int RULE_VERSION_MAX_LENGTH = 64;

    private final ChallengeInboxRepository inbox;
    private final ChallengeResultRepository results;
    private final ChallengeSettlementStatusRepository settlements;
    private final ObjectMapper objectMapper;

    @Transactional
    public SettlementApplyResult apply(String partitionKey, String payload) {
        SettlementCompletedEvent event = parse(payload);
        if (!isValid(event, partitionKey)) {
            log.warn("Settlement event is invalid, event_id={}",
                    event == null ? null : event.eventId());
            return SettlementApplyResult.QUARANTINED;
        }

        UUID eventId = event.eventId();
        byte[] digest = RequestFingerprint.sha256(payload);
        Optional<byte[]> processedDigest = inbox.findDigest(CONSUMER, eventId);
        if (processedDigest.isPresent()) {
            return duplicateOrQuarantine(processedDigest.get(), digest, eventId);
        }

        SettlementCompletedData data = event.data();
        Optional<ChallengeResultRepository.ResultRow> result = results.findByResultId(data.resultId());
        if (result.isEmpty()) {
            // TODO(spec-020): сохранять событие в challenge_pending_events (WAITING);
            // в MVP доставка повторяется Kafka, inbox не отмечается применённым.
            throw new SettlementNotReadyException(data.resultId());
        }
        if (!matches(result.get(), data)) {
            log.warn("Settlement event does not match the final result, event_id={}, challenge_id={}, "
                    + "result_id={}", eventId, data.challengeId(), data.resultId());
            return SettlementApplyResult.QUARANTINED;
        }

        boolean projectionExisted = false;
        Optional<ChallengeSettlementStatusRepository.SettlementRow> projection =
                settlements.findByChallengeId(data.challengeId());
        if (projection.isPresent()) {
            projectionExisted = true;
            if (!matches(projection.get(), data)) {
                log.warn("Settlement projection conflicts with the applied settlement, event_id={}, "
                        + "challenge_id={}", eventId, data.challengeId());
                return SettlementApplyResult.QUARANTINED;
            }
        } else {
            settlements.insert(data.challengeId(), data.resultId(), data.settlementId(), eventId,
                    data.completedAt());
            var stored = settlements.findByChallengeId(data.challengeId())
                    .orElseThrow(() -> new IllegalStateException("Settlement projection was not stored"));
            if (!matches(stored, data)) {
                log.warn("Settlement projection conflicts with the concurrent settlement, event_id={}, "
                        + "challenge_id={}", eventId, data.challengeId());
                return SettlementApplyResult.QUARANTINED;
            }
        }

        if (inbox.insertIfAbsent(CONSUMER, eventId, digest) == 0) {
            byte[] storedDigest = inbox.findDigest(CONSUMER, eventId)
                    .orElseThrow(() -> new IllegalStateException("Inbox record disappeared"));
            if (!Arrays.equals(storedDigest, digest)) {
                throw new SettlementDigestConflictException(eventId);
            }
            return SettlementApplyResult.DUPLICATE;
        }
        return projectionExisted ? SettlementApplyResult.DUPLICATE : SettlementApplyResult.APPLIED;
    }

    private SettlementApplyResult duplicateOrQuarantine(byte[] storedDigest, byte[] incomingDigest,
                                                       UUID eventId) {
        if (Arrays.equals(storedDigest, incomingDigest)) {
            return SettlementApplyResult.DUPLICATE;
        }
        log.error("Settlement event digest conflicts with the processed event, event_id={}", eventId);
        // TODO(spec-020): сохранять конфликт digest в challenge_pending_events (QUARANTINED);
        // в MVP событие не применяется, а evidence ограничено логом.
        return SettlementApplyResult.QUARANTINED;
    }

    private boolean matches(ChallengeResultRepository.ResultRow result, SettlementCompletedData data) {
        return result.challengeId().equals(data.challengeId())
                && result.setVersion() == data.setVersion()
                && result.ruleVersion().equals(data.ruleVersion());
    }

    private boolean matches(ChallengeSettlementStatusRepository.SettlementRow settlement,
                            SettlementCompletedData data) {
        return settlement.resultId().equals(data.resultId())
                && settlement.settlementId().equals(data.settlementId());
    }

    private SettlementCompletedEvent parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, SettlementCompletedEvent.class);
        } catch (JacksonException exception) {
            return null;
        }
    }

    private boolean isValid(SettlementCompletedEvent event, String partitionKey) {
        if (event == null || event.data() == null) {
            return false;
        }
        SettlementCompletedData data = event.data();
        return EVENT_TYPE.equals(event.eventType())
                && event.schemaVersion() == SCHEMA_VERSION
                && PRODUCER.equals(event.producer())
                && event.eventId() != null
                && event.occurredAt() != null
                && event.correlationId() != null
                && matchesKey(partitionKey, data.challengeId())
                && data.settlementId() != null
                && data.resultId() != null
                && data.setVersion() > 0
                && data.ruleVersion() != null
                && !data.ruleVersion().isBlank()
                && data.ruleVersion().length() <= RULE_VERSION_MAX_LENGTH
                && data.completedAt() != null;
    }

    private boolean matchesKey(String partitionKey, UUID challengeId) {
        if (partitionKey == null || challengeId == null) {
            return false;
        }
        try {
            return challengeId.equals(UUID.fromString(partitionKey));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
