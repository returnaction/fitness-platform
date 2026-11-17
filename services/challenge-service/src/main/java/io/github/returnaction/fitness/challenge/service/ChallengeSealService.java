package io.github.returnaction.fitness.challenge.service;

import io.github.returnaction.fitness.challenge.coins.CoinsStakeClient;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsOperationConflictException;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsOutcomeUnknownException;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsRejectedException;
import io.github.returnaction.fitness.challenge.domain.ChallengeStatus;
import io.github.returnaction.fitness.challenge.entity.ChallengeEntity;
import io.github.returnaction.fitness.challenge.repository.ChallengeOperationRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeParticipantRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeSealRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeSealRepository.SealItem;
import io.github.returnaction.fitness.contracts.coins.stake.v1.BeginStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FinalizeStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.PutStakeBatchRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.SealedStake;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Сохраняет снимок набора после заполнения и закрывает его в Coins. До
 * подтверждённого FinalizeStakeSet challenge остаётся OPEN.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChallengeSealService {

    private final ChallengeRepository challenges;
    private final ChallengeParticipantRepository participants;
    private final ChallengeOperationRepository operations;
    private final ChallengeSealRepository sealRepository;
    private final CoinsStakeClient coins;
    private final PlatformTransactionManager transactionManager;

    public void sealIfFull(UUID challengeId) {
        Snapshot snapshot = prepare(challengeId);
        if (snapshot != null) {
            seal(snapshot);
        }
    }

    public void resume(UUID challengeId) {
        var existing = sealRepository.findByChallengeId(challengeId).orElse(null);
        if (existing == null || !"COLLECTING".equals(existing.state())) {
            return;
        }
        var challenge = challenges.findById(challengeId).orElse(null);
        if (challenge == null || challenge.getStatus() != ChallengeStatus.OPEN) {
            return;
        }
        List<SealItem> items = sealRepository.findItems(challengeId, existing.version());
        if (items.isEmpty()) {
            return;
        }
        seal(new Snapshot(challengeId, existing.version(), existing.sealOperationId(), items));
    }

    private Snapshot prepare(UUID challengeId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ChallengeEntity challenge = challenges.findByIdForUpdate(challengeId).orElse(null);
            if (challenge == null || challenge.getStatus() != ChallengeStatus.OPEN) {
                return null;
            }
            int max = challenge.getMaxParticipants() == null ? 2 : challenge.getMaxParticipants();
            if (participants.countByChallengeId(challengeId) < max) {
                return null;
            }
            var existing = sealRepository.findByChallengeId(challengeId);
            if (existing.isPresent()) {
                return "COLLECTING".equals(existing.get().state())
                        ? new Snapshot(challengeId, existing.get().version(), existing.get().sealOperationId(),
                        sealRepository.findItems(challengeId, existing.get().version()))
                        : null;
            }

            List<SealItem> items = operations.findConfirmedWithHold(challengeId).stream()
                    .map(operation -> new SealItem(operation.getHoldId(), operation.getUserId(),
                            Objects.requireNonNull(challenge.getStakeAmount(), "stakeAmount")))
                    .toList();
            if (items.size() != max) {
                return null;
            }
            UUID sealOperationId = UUID.randomUUID();
            long version = 1;
            sealRepository.insertSet(challengeId, version, sealOperationId);
            for (SealItem item : items) {
                sealRepository.insertItem(challengeId, version, item.holdId(), item.userId(), item.amount());
            }
            return new Snapshot(challengeId, version, sealOperationId, items);
        });
    }

    private void seal(Snapshot snapshot) {
        byte[] digest = ChallengeStakeDigest.digest(snapshot.items());
        try {
            coins.beginStakeSet(BeginStakeSetRequest.newBuilder()
                    .setSealOperationId(snapshot.sealOperationId().toString())
                    .setChallengeId(snapshot.challengeId().toString())
                    .setSetVersion(snapshot.version())
                    .setExpectedCount(snapshot.items().size())
                    .setExpectedDigest(ChallengeStakeDigest.toHex(digest))
                    .build());
            coins.putStakeBatch(PutStakeBatchRequest.newBuilder()
                    .setSealOperationId(snapshot.sealOperationId().toString())
                    .setBatchNumber(0)
                    .addAllStakes(snapshot.items().stream()
                            .map(item -> SealedStake.newBuilder()
                                    .setHoldId(item.holdId().toString())
                                    .setUserId(item.userId().toString())
                                    .setAmount(item.amount())
                                    .build())
                            .toList())
                    .build());
            coins.finalizeStakeSet(FinalizeStakeSetRequest.newBuilder()
                    .setSealOperationId(snapshot.sealOperationId().toString())
                    .build());
        } catch (CoinsOutcomeUnknownException | CoinsRejectedException
                 | CoinsOperationConflictException exception) {
            log.debug("Seal is not confirmed yet, challenge_id={}", snapshot.challengeId());
            return;
        }

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ChallengeEntity challenge = challenges.findByIdForUpdate(snapshot.challengeId()).orElse(null);
            if (challenge == null || challenge.getStatus() != ChallengeStatus.OPEN) {
                return;
            }
            Instant now = Instant.now();
            challenge.activate(now);
            for (var participant : participants.findByChallengeIdOrderByJoinSequenceAsc(snapshot.challengeId())) {
                participant.activate(now);
            }
            sealRepository.markSealed(snapshot.challengeId(), digest);
        });
    }

    private record Snapshot(UUID challengeId, long version, UUID sealOperationId, List<SealItem> items) {
    }
}
