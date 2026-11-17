package io.github.returnaction.fitness.challenge.service;

import static io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseReason.RELEASE_REASON_CHALLENGE_CANCELLED;

import io.github.returnaction.fitness.challenge.coins.CoinsStakeClient;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsOperationConflictException;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsOutcomeUnknownException;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsRejectedException;
import io.github.returnaction.fitness.challenge.domain.ChallengeStatus;
import io.github.returnaction.fitness.challenge.domain.RefundStatus;
import io.github.returnaction.fitness.challenge.domain.ReleaseIntentStatus;
import io.github.returnaction.fitness.challenge.domain.ReleaseReason;
import io.github.returnaction.fitness.challenge.dto.CancelResponse;
import io.github.returnaction.fitness.challenge.entity.ChallengeEntity;
import io.github.returnaction.fitness.challenge.entity.ChallengeReleaseIntentEntity;
import io.github.returnaction.fitness.challenge.idempotency.IdempotencyService;
import io.github.returnaction.fitness.challenge.idempotency.RequestFingerprint;
import io.github.returnaction.fitness.challenge.idempotency.StoredHttpResponse;
import io.github.returnaction.fitness.challenge.repository.ChallengeOperationRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeParticipantRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeReleaseIntentRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeRepository;
import io.github.returnaction.fitness.challenge.shared.exception.BusinessConflictException;
import io.github.returnaction.fitness.challenge.shared.exception.ResourceNotFoundException;
import io.github.returnaction.fitness.contracts.coins.stake.v1.CompleteStakeCancellationRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FreezeStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseStakeRequest;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Отмена доступна только создателю и только без других участников. Возврат
 * выполняется через release intent: freeze → release → complete.
 */
@Service
@RequiredArgsConstructor
public class ChallengeCancelService {

    private final ChallengeRepository challenges;
    private final ChallengeParticipantRepository participants;
    private final ChallengeOperationRepository operations;
    private final ChallengeReleaseIntentRepository releaseIntents;
    private final IdempotencyService idempotency;
    private final CoinsStakeClient coins;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    public CancelResult cancel(UUID userId, UUID challengeId, String idempotencyKey) {
        UUID key = idempotency.requireKey(idempotencyKey);
        String path = "/api/v1/challenges/" + challengeId + "/cancel";
        byte[] fingerprint = RequestFingerprint.sha256("{}");

        var replay = idempotency.findReplay(userId, "POST", path, key, fingerprint);
        if (replay.isPresent()) {
            return readStored(replay.get());
        }

        Cancellation cancellation = prepare(userId, challengeId);
        RefundStatus refund = cancellation.status() == ReleaseIntentStatus.CONFIRMED
                ? RefundStatus.COMPLETED
                : refundThroughCoins(cancellation);

        var body = new CancelResponse(challengeId, ChallengeStatus.CANCELLED, refund);
        idempotency.store(userId, "POST", path, key, fingerprint,
                new StoredHttpResponse(200, json(body), "{}"));
        return new CancelResult(200, body);
    }

    private Cancellation prepare(UUID userId, UUID challengeId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ChallengeEntity challenge = challenges.findByIdForUpdate(challengeId)
                    .orElseThrow(ChallengeCancelService::challengeNotFound);
            if (!challenge.getCreatorUserId().equals(userId)) {
                throw challengeNotFound();
            }
            if (challenge.getStatus() == ChallengeStatus.CANCELLED) {
                ChallengeReleaseIntentEntity existing = releaseIntents.findLatestByChallengeId(challengeId)
                        .orElseThrow(ChallengeCancelService::operationNotFound);
                return new Cancellation(existing.getId(), existing.getHoldId(), challengeId,
                        existing.getStatus());
            }
            if (challenge.getStatus() != ChallengeStatus.OPEN) {
                throw new BusinessConflictException("challenge_not_editable", "Challenge is not cancellable.");
            }
            if (participants.countByChallengeId(challengeId) > 1) {
                throw new BusinessConflictException("cancellation_not_allowed",
                        "Challenge has other participants.");
            }

            var publish = operations.findConfirmedPublishWithHold(challengeId, userId)
                    .orElseThrow(ChallengeCancelService::operationNotFound);
            ChallengeReleaseIntentEntity intent = releaseIntents.findByHoldId(publish.getHoldId())
                    .orElseGet(() -> releaseIntents.save(new ChallengeReleaseIntentEntity(
                            challengeId, publish.getHoldId(), ReleaseReason.CHALLENGE_CANCELLED,
                            Instant.now())));
            challenge.cancel(Instant.now());
            return new Cancellation(intent.getId(), intent.getHoldId(), challengeId, intent.getStatus());
        });
    }

    private RefundStatus refundThroughCoins(Cancellation cancellation) {
        try {
            coins.freezeStakeSet(FreezeStakeSetRequest.newBuilder()
                    .setCancellationId(cancellation.id().toString())
                    .setChallengeId(cancellation.challengeId().toString())
                    .build());
            coins.releaseStake(ReleaseStakeRequest.newBuilder()
                    .setReleaseOperationId(cancellation.id().toString())
                    .setHoldId(cancellation.holdId().toString())
                    .setChallengeId(cancellation.challengeId().toString())
                    .setReason(RELEASE_REASON_CHALLENGE_CANCELLED)
                    .build());
        } catch (CoinsOutcomeUnknownException | CoinsRejectedException
                 | CoinsOperationConflictException exception) {
            return RefundStatus.PENDING;
        }

        markConfirmed(cancellation.id());
        try {
            coins.completeStakeCancellation(CompleteStakeCancellationRequest.newBuilder()
                    .setCancellationId(cancellation.id().toString())
                    .setChallengeId(cancellation.challengeId().toString())
                    .build());
        } catch (CoinsOutcomeUnknownException | CoinsRejectedException
                 | CoinsOperationConflictException exception) {
            // Возврат уже подтверждён release; complete повторит recovery worker.
        }
        return RefundStatus.COMPLETED;
    }

    private void markConfirmed(UUID intentId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> releaseIntents
                .findById(intentId)
                .orElseThrow(ChallengeCancelService::operationNotFound)
                .confirm(Instant.now()));
    }

    private CancelResult readStored(StoredHttpResponse stored) {
        return new CancelResult(stored.status(),
                objectMapper.readValue(stored.body(), CancelResponse.class));
    }

    private String json(Object body) {
        return objectMapper.writeValueAsString(body);
    }

    private static ResourceNotFoundException challengeNotFound() {
        return new ResourceNotFoundException("challenge_not_found", "Challenge not found.");
    }

    private static ResourceNotFoundException operationNotFound() {
        return new ResourceNotFoundException("operation_not_found", "Operation not found.");
    }

    public record CancelResult(int status, Object body) {
    }

    private record Cancellation(UUID id, UUID holdId, UUID challengeId, ReleaseIntentStatus status) {
    }
}
