package io.github.returnaction.fitness.challenge.service;

import static io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseReason.RELEASE_REASON_CHALLENGE_CANCELLED;

import io.github.returnaction.fitness.challenge.coins.CoinsStakeClient;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsOperationConflictException;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsOutcomeUnknownException;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsRejectedException;
import io.github.returnaction.fitness.challenge.domain.ChallengeOperationStatus;
import io.github.returnaction.fitness.challenge.entity.ChallengeOperationEntity;
import io.github.returnaction.fitness.challenge.repository.ChallengeOperationRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeRecoveryRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeReleaseIntentRepository;
import io.github.returnaction.fitness.contracts.coins.stake.v1.CompleteStakeCancellationRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FreezeStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.GetStakeOperationRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseOutcome;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseStakeRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveOutcome;
import io.github.returnaction.fitness.contracts.coins.stake.v1.StakeOperationKind;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Продолжает сохранённые операции после неизвестного ответа Coins. Hold не
 * освобождается по таймеру: только release intent или невозможность
 * подтверждения места запускают возврат.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChallengeRecoveryWorker {

    private final ChallengeRecoveryRepository recovery;
    private final ChallengeOperationRepository operations;
    private final ChallengeReleaseIntentRepository releaseIntents;
    private final ChallengePublishService publishService;
    private final ChallengeJoinService joinService;
    private final CoinsStakeClient coins;
    private final PlatformTransactionManager transactionManager;

    @Value("${challenge.recovery.lease:30s}")
    private Duration lease;

    @Value("${challenge.recovery.batch:20}")
    private int batch;

    @Scheduled(fixedDelayString = "${challenge.recovery.fixed-delay:5s}")
    public void recover() {
        int leaseSeconds = Math.toIntExact(lease.toSeconds());
        for (var claim : recovery.claimOperations(batch, leaseSeconds)) {
            try {
                recoverOperation(claim);
            } finally {
                recovery.clearOperationLease(claim.id());
            }
        }
        for (var claim : recovery.claimReleaseIntents(batch, leaseSeconds)) {
            try {
                recoverReleaseIntent(claim);
            } finally {
                recovery.clearIntentLease(claim.id());
            }
        }
    }

    private void recoverOperation(ChallengeRecoveryRepository.OperationClaim claim) {
        try {
            var response = coins.getStakeOperation(GetStakeOperationRequest.newBuilder()
                    .setKind(StakeOperationKind.STAKE_OPERATION_KIND_RESERVE)
                    .setOperationId(claim.id().toString())
                    .build());
            if (!response.hasReserve()) {
                return;
            }
            var reserve = response.getReserve();
            if (reserve.getOutcome() == ReserveOutcome.RESERVE_OUTCOME_RESERVED
                    && !reserve.getHoldId().isBlank()) {
                UUID holdId = UUID.fromString(reserve.getHoldId());
                if ("PUBLISH".equals(claim.kind())) {
                    publishService.recover(claim.userId(), claim.challengeId(), claim.id(), holdId);
                } else if ("JOIN".equals(claim.kind())) {
                    joinService.recover(claim.userId(), claim.challengeId(), claim.id(), holdId);
                }
            } else if (reserve.getOutcome() == ReserveOutcome.RESERVE_OUTCOME_INSUFFICIENT_FUNDS) {
                failOperation(claim.id(), "insufficient_coins");
            } else if (reserve.getOutcome() == ReserveOutcome.RESERVE_OUTCOME_ACTIVE_HOLD_EXISTS) {
                failOperation(claim.id(), "stake_conflict");
            }
        } catch (CoinsRejectedException exception) {
            // NOT_FOUND не доказывает отсутствие команды в пути; повторяем позже.
            log.debug("Recovery operation is not resolvable yet, operation_id={}", claim.id());
        } catch (CoinsOutcomeUnknownException exception) {
            log.debug("Recovery outcome is still unknown, operation_id={}", claim.id());
        }
    }

    private void recoverReleaseIntent(ChallengeRecoveryRepository.IntentClaim claim) {
        try {
            var response = coins.getStakeOperation(GetStakeOperationRequest.newBuilder()
                    .setKind(StakeOperationKind.STAKE_OPERATION_KIND_RELEASE)
                    .setOperationId(claim.id().toString())
                    .build());
            if (response.hasRelease()
                    && response.getRelease().getOutcome() == ReleaseOutcome.RELEASE_OUTCOME_RELEASED) {
                confirmIntent(claim.id());
                return;
            }
        } catch (CoinsRejectedException exception) {
            // Исход ещё неизвестен; повторяем ту же release-команду.
        } catch (CoinsOutcomeUnknownException exception) {
            return;
        }

        try {
            coins.freezeStakeSet(FreezeStakeSetRequest.newBuilder()
                    .setCancellationId(claim.id().toString())
                    .setChallengeId(claim.challengeId().toString())
                    .build());
            coins.releaseStake(ReleaseStakeRequest.newBuilder()
                    .setReleaseOperationId(claim.id().toString())
                    .setHoldId(claim.holdId().toString())
                    .setChallengeId(claim.challengeId().toString())
                    .setReason(RELEASE_REASON_CHALLENGE_CANCELLED)
                    .build());
        } catch (CoinsOutcomeUnknownException | CoinsRejectedException
                 | CoinsOperationConflictException exception) {
            return;
        }
        confirmIntent(claim.id());
        try {
            coins.completeStakeCancellation(CompleteStakeCancellationRequest.newBuilder()
                    .setCancellationId(claim.id().toString())
                    .setChallengeId(claim.challengeId().toString())
                    .build());
        } catch (CoinsOutcomeUnknownException | CoinsRejectedException
                 | CoinsOperationConflictException exception) {
            // Возврат уже подтверждён release; complete повторит следующий цикл.
        }
    }

    private void failOperation(UUID operationId, String failureCode) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ChallengeOperationEntity operation = operations.findById(operationId).orElse(null);
            if (operation != null && operation.getStatus() != ChallengeOperationStatus.CONFIRMED
                    && operation.getStatus() != ChallengeOperationStatus.FAILED) {
                operation.fail(failureCode, Instant.now());
            }
        });
    }

    private void confirmIntent(UUID intentId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> releaseIntents
                .findById(intentId)
                .ifPresent(intent -> {
                    if (intent.getStatus() == io.github.returnaction.fitness.challenge.domain
                            .ReleaseIntentStatus.PENDING) {
                        intent.confirm(Instant.now());
                    }
                }));
    }
}
