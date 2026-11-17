package io.github.returnaction.fitness.coins.settlement;

import io.github.returnaction.fitness.coins.outbox.CoinOutboxRepository;
import io.github.returnaction.fitness.coins.settlement.SettlementRepository.ActiveHold;
import io.github.returnaction.fitness.coins.settlement.SettlementRepository.SealedHold;
import io.github.returnaction.fitness.coins.settlement.SettlementRepository.SettlementSnapshot;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Один локальный расчёт для двух holds: проверяет закрытый набор и победителей,
 * переводит holds в SETTLED, начисляет банк/возвращает ставки и пишет
 * `coins.settlement_completed` в outbox вместе с inbox.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    static final String CONSUMER = "coins-service.challenge-finished";
    private static final String SINGLE_WINNER = "SINGLE_WINNER";
    private static final String DRAW = "DRAW";
    private static final String SEALED = "SEALED";
    private static final String COMPLETED = "COMPLETED";
    private static final String COMPLETION_EVENT = "coins.settlement_completed";
    private static final String PRODUCER = "coins";
    private static final int SCHEMA_VERSION = 1;
    private static final int EXPECTED_STAKES = 2;

    private final SettlementRepository repository;
    private final CoinInboxRepository inbox;
    private final CoinOutboxRepository outbox;
    private final ObjectMapper objectMapper;

    @Transactional
    public SettlementApplyResult settle(UUID eventId, byte[] payloadHash, ChallengeFinishedData data,
                                        List<UUID> winners) {
        var stakeSet = repository.lockStakeSet(data.challengeId()).orElse(null);
        var existing = repository.findSettlementByResultId(data.resultId());
        if (existing.isPresent()) {
            return replayOrQuarantine(existing.get(), eventId, payloadHash, data);
        }
        if (stakeSet == null
                || !SEALED.equals(stakeSet.state())
                || stakeSet.setVersion() != data.setVersion()
                || !data.setDigest().equals(stakeSet.digestHex())) {
            log.warn("Settlement stake set does not match, event_id={}, challenge_id={}",
                    eventId, data.challengeId());
            return SettlementApplyResult.QUARANTINED;
        }

        if (repository.findSettlementByChallengeId(data.challengeId()).isPresent()) {
            log.warn("Another settlement already exists for challenge, event_id={}, challenge_id={}",
                    eventId, data.challengeId());
            return SettlementApplyResult.QUARANTINED;
        }

        List<SealedHold> sealed = repository.findSealedStakes(data.challengeId(), data.setVersion());
        if (sealed.size() != EXPECTED_STAKES) {
            log.warn("Settlement stake count is invalid, event_id={}, challenge_id={}",
                    eventId, data.challengeId());
            return SettlementApplyResult.QUARANTINED;
        }
        Map<UUID, SealedHold> sealedByUser = new HashMap<>();
        long pool = 0;
        for (SealedHold hold : sealed) {
            sealedByUser.put(hold.userId(), hold);
            try {
                pool = Math.addExact(pool, hold.amount());
            } catch (ArithmeticException exception) {
                log.warn("Settlement pool overflow, event_id={}, challenge_id={}", eventId, data.challengeId());
                return SettlementApplyResult.QUARANTINED;
            }
        }

        List<UUID> sortedWinners = new ArrayList<>(new HashSet<>(winners));
        sortedWinners.sort(Comparator.naturalOrder());
        if (!validWinners(data, winners, sortedWinners, sealedByUser, pool)) {
            log.warn("Settlement winners do not match the final result, event_id={}, result_id={}",
                    eventId, data.resultId());
            return SettlementApplyResult.QUARANTINED;
        }
        List<Allocation> allocations = allocations(data.outcome(), sortedWinners, sealedByUser, pool);
        if (allocations == null) {
            log.warn("Settlement outcome is unsupported, event_id={}, outcome={}", eventId, data.outcome());
            return SettlementApplyResult.QUARANTINED;
        }

        List<ActiveHold> active = repository.lockActiveHolds(data.challengeId());
        if (!sameStakes(sealed, active)) {
            log.warn("Active holds do not match the sealed set, event_id={}, challenge_id={}",
                    eventId, data.challengeId());
            return SettlementApplyResult.QUARANTINED;
        }
        Map<UUID, SettlementRepository.WalletBalances> wallets = lockWallets(sealed);
        if (wallets.size() != sealed.size()) {
            log.warn("Settlement wallet is missing, event_id={}, challenge_id={}",
                    eventId, data.challengeId());
            return SettlementApplyResult.QUARANTINED;
        }

        UUID settlementId = UUID.randomUUID();
        if (repository.insertSettlement(settlementId, data.resultId(), data.challengeId(),
                data.setVersion(), data.ruleVersion(), pool, data.winnerCount(),
                SettlementDigest.fromHex(data.winnersDigest())) == 0) {
            SettlementSnapshot raced = repository.findSettlementByResultId(data.resultId())
                    .orElseThrow(() -> new IllegalStateException("Settlement conflict without a stored row"));
            return replayOrQuarantine(raced, eventId, payloadHash, data);
        }

        applySettlement(settlementId, data, sealed, allocations, wallets);
        repository.markStakeSetSettled(data.challengeId(), data.setVersion());
        if (repository.completeSettlement(settlementId, data.winnerCount()) != 1) {
            throw new IllegalStateException("Settlement could not be completed");
        }
        Instant completedAt = Instant.now();
        UUID completionEventId = UUID.randomUUID();
        outbox.insert(completionEventId, COMPLETION_EVENT, SCHEMA_VERSION, data.challengeId(),
                completionPayload(completionEventId, settlementId, data, completedAt));
        if (inbox.insertIfAbsent(CONSUMER, eventId, payloadHash) == 0) {
            byte[] storedHash = inbox.findHash(CONSUMER, eventId)
                    .orElseThrow(() -> new IllegalStateException("Inbox record disappeared"));
            if (!MessageDigest.isEqual(storedHash, payloadHash)) {
                throw new SettlementDigestConflictException(eventId);
            }
            return SettlementApplyResult.DUPLICATE;
        }
        return SettlementApplyResult.APPLIED;
    }

    private SettlementApplyResult replayOrQuarantine(SettlementSnapshot settlement, UUID eventId,
                                                     byte[] payloadHash, ChallengeFinishedData data) {
        if (settlement.resultId().equals(data.resultId())
                && settlement.challengeId().equals(data.challengeId())
                && settlement.setVersion() == data.setVersion()
                && settlement.ruleVersion().equals(data.ruleVersion())
                && settlement.winnerCount() == data.winnerCount()
                && settlement.winnersDigestHex().equals(data.winnersDigest())
                && COMPLETED.equals(settlement.phase())) {
            if (inbox.insertIfAbsent(CONSUMER, eventId, payloadHash) == 0) {
                byte[] storedHash = inbox.findHash(CONSUMER, eventId)
                        .orElseThrow(() -> new IllegalStateException("Inbox record disappeared"));
                if (!MessageDigest.isEqual(storedHash, payloadHash)) {
                    throw new SettlementDigestConflictException(eventId);
                }
            }
            return SettlementApplyResult.DUPLICATE;
        }
        log.warn("Settlement replay conflicts with stored result, event_id={}, result_id={}",
                eventId, data.resultId());
        return SettlementApplyResult.QUARANTINED;
    }

    private boolean validWinners(ChallengeFinishedData data, List<UUID> winners, List<UUID> sortedWinners,
                                 Map<UUID, SealedHold> sealedByUser, long pool) {
        if (new HashSet<>(winners).size() != winners.size()
                || sortedWinners.size() != data.winnerCount()) {
            return false;
        }
        if (!data.winnersDigest().equals(SettlementDigest.toHex(SettlementDigest.winnersDigest(sortedWinners)))) {
            return false;
        }
        for (UUID winner : sortedWinners) {
            if (!sealedByUser.containsKey(winner)) {
                return false;
            }
        }
        return switch (data.outcome()) {
            case SINGLE_WINNER -> sortedWinners.size() == 1;
            case DRAW -> sortedWinners.size() == 2 && allocatedTotal(sortedWinners, sealedByUser) == pool;
            default -> false;
        };
    }

    private long allocatedTotal(List<UUID> winners, Map<UUID, SealedHold> sealedByUser) {
        long total = 0;
        for (UUID winner : winners) {
            total += sealedByUser.get(winner).amount();
        }
        return total;
    }

    private List<Allocation> allocations(String outcome, List<UUID> winners,
                                         Map<UUID, SealedHold> sealedByUser, long pool) {
        if (SINGLE_WINNER.equals(outcome)) {
            return List.of(new Allocation(winners.getFirst(), pool));
        }
        if (DRAW.equals(outcome)) {
            List<Allocation> allocations = new ArrayList<>();
            for (UUID winner : winners) {
                allocations.add(new Allocation(winner, sealedByUser.get(winner).amount()));
            }
            return allocations;
        }
        return null;
    }

    private boolean sameStakes(List<SealedHold> sealed, List<ActiveHold> active) {
        if (sealed.size() != active.size()) {
            return false;
        }
        for (int index = 0; index < sealed.size(); index++) {
            SealedHold expected = sealed.get(index);
            ActiveHold actual = active.get(index);
            if (!expected.holdId().equals(actual.holdId())
                    || !expected.userId().equals(actual.userId())
                    || expected.amount() != actual.amount()) {
                return false;
            }
        }
        return true;
    }

    private Map<UUID, SettlementRepository.WalletBalances> lockWallets(List<SealedHold> sealed) {
        Map<UUID, SettlementRepository.WalletBalances> wallets = new HashMap<>();
        Set<UUID> users = new TreeSet<>();
        for (SealedHold hold : sealed) {
            users.add(hold.userId());
        }
        for (UUID userId : users) {
            repository.lockWallet(userId).ifPresent(wallet -> wallets.put(userId, wallet));
        }
        return wallets;
    }

    private void applySettlement(UUID settlementId, ChallengeFinishedData data, List<SealedHold> sealed,
                                 List<Allocation> allocations,
                                 Map<UUID, SettlementRepository.WalletBalances> wallets) {
        Map<UUID, Long> availableDelta = new HashMap<>();
        Map<UUID, Long> lockedDelta = new HashMap<>();
        int entryIndex = 0;
        for (SealedHold hold : sealed) {
            repository.markHoldSettled(hold.holdId());
            repository.insertHoldMove(settlementId, hold.holdId(), hold.amount());
            repository.insertLedgerEntry(UUID.randomUUID(), hold.userId(), settlementId, entryIndex++,
                    0, -hold.amount(), data.challengeId());
            lockedDelta.merge(hold.userId(), -hold.amount(), Long::sum);
        }
        for (Allocation allocation : allocations) {
            repository.insertSettlementWinner(settlementId, allocation.userId());
            repository.insertAllocation(settlementId, allocation.userId(), allocation.amount());
            if (allocation.amount() > 0) {
                repository.insertLedgerEntry(UUID.randomUUID(), allocation.userId(), settlementId,
                        entryIndex++, allocation.amount(), 0, data.challengeId());
            }
            availableDelta.merge(allocation.userId(), allocation.amount(), Long::sum);
        }
        for (Map.Entry<UUID, SettlementRepository.WalletBalances> entry : wallets.entrySet()) {
            UUID userId = entry.getKey();
            long available = Math.addExact(entry.getValue().availableBalance(),
                    availableDelta.getOrDefault(userId, 0L));
            long locked = Math.addExact(entry.getValue().lockedBalance(),
                    lockedDelta.getOrDefault(userId, 0L));
            if (available < 0 || locked < 0) {
                throw new IllegalStateException("Wallet balance would become negative");
            }
            repository.updateWallet(userId, available, locked);
        }
    }

    private String completionPayload(UUID eventId, UUID settlementId, ChallengeFinishedData data,
                                     Instant completedAt) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("event_id", eventId.toString());
        payload.put("event_type", COMPLETION_EVENT);
        payload.put("schema_version", SCHEMA_VERSION);
        payload.put("occurred_at", completedAt.toString());
        payload.put("producer", PRODUCER);
        payload.put("correlation_id", data.resultId().toString());
        var details = new LinkedHashMap<String, Object>();
        details.put("settlementId", settlementId.toString());
        details.put("challengeId", data.challengeId().toString());
        details.put("resultId", data.resultId().toString());
        details.put("setVersion", data.setVersion());
        details.put("ruleVersion", data.ruleVersion());
        details.put("completedAt", completedAt.toString());
        payload.put("data", details);
        return objectMapper.writeValueAsString(payload);
    }

    private record Allocation(UUID userId, long amount) {
    }
}
