package io.github.returnaction.fitness.coins.settlement;

import io.github.returnaction.fitness.coins.challenge.ChallengeResultClient;
import io.github.returnaction.fitness.coins.challenge.ChallengeResultRejectedException;
import io.github.returnaction.fitness.coins.stake.RequestHash;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Разбирает `challenge.finished`, читает победителей по mTLS до открытия
 * транзакции и передаёт расчёт в {@link SettlementService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChallengeFinishedProcessor {

    private static final String EVENT_TYPE = "challenge.finished";
    private static final String PRODUCER = "challenge";
    private static final String SINGLE_WINNER = "SINGLE_WINNER";
    private static final String DRAW = "DRAW";
    private static final int SCHEMA_VERSION = 1;
    private static final int RULE_VERSION_MAX_LENGTH = 64;
    private static final int DIGEST_HEX_LENGTH = 64;

    private final CoinInboxRepository inbox;
    private final ChallengeResultClient challengeResults;
    private final SettlementService settlementService;
    private final ObjectMapper objectMapper;

    public SettlementApplyResult handle(String partitionKey, String payload) {
        ChallengeFinishedEvent event = parse(payload);
        if (!isValid(event, partitionKey)) {
            log.warn("Challenge finished event is invalid, event_id={}",
                    event == null ? null : event.eventId());
            return SettlementApplyResult.QUARANTINED;
        }

        UUID eventId = event.eventId();
        byte[] payloadHash = RequestHash.sha256(payload);
        Optional<byte[]> processedHash = inbox.findHash(SettlementService.CONSUMER, eventId);
        if (processedHash.isPresent()) {
            if (Arrays.equals(processedHash.get(), payloadHash)) {
                return SettlementApplyResult.DUPLICATE;
            }
            // TODO(spec-020): сохранять конфликт digest в pending/quarantine-таблице;
            // в MVP evidence ограничено логом.
            log.error("Challenge finished event digest conflicts with the processed event, event_id={}",
                    eventId);
            return SettlementApplyResult.QUARANTINED;
        }

        ChallengeFinishedData data = event.data();
        List<UUID> winners;
        try {
            winners = challengeResults.listWinners(data.resultId());
        } catch (ChallengeResultRejectedException exception) {
            log.warn("Challenge winners were rejected, event_id={}, result_id={}, reason={}",
                    eventId, data.resultId(), exception.getReason());
            return SettlementApplyResult.QUARANTINED;
        }
        return settlementService.settle(eventId, payloadHash, data, winners);
    }

    private ChallengeFinishedEvent parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, ChallengeFinishedEvent.class);
        } catch (JacksonException exception) {
            return null;
        }
    }

    private boolean isValid(ChallengeFinishedEvent event, String partitionKey) {
        if (event == null || event.data() == null) {
            return false;
        }
        ChallengeFinishedData data = event.data();
        return EVENT_TYPE.equals(event.eventType())
                && event.schemaVersion() == SCHEMA_VERSION
                && PRODUCER.equals(event.producer())
                && event.eventId() != null
                && event.occurredAt() != null
                && event.correlationId() != null
                && matchesKey(partitionKey, data.challengeId())
                && data.resultId() != null
                && data.setVersion() > 0
                && isDigest(data.setDigest())
                && data.ruleVersion() != null
                && !data.ruleVersion().isBlank()
                && data.ruleVersion().length() <= RULE_VERSION_MAX_LENGTH
                && validOutcome(data.outcome(), data.winnerCount())
                && isDigest(data.winnersDigest());
    }

    private boolean validOutcome(String outcome, long winnerCount) {
        if (SINGLE_WINNER.equals(outcome)) {
            return winnerCount == 1;
        }
        return DRAW.equals(outcome) && winnerCount == 2;
    }

    private boolean isDigest(String value) {
        return value != null && value.length() == DIGEST_HEX_LENGTH && value.matches("[0-9a-f]{64}");
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
