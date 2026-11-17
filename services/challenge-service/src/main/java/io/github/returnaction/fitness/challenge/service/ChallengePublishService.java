package io.github.returnaction.fitness.challenge.service;

import io.github.returnaction.fitness.challenge.coins.CoinsReserveRejection;
import io.github.returnaction.fitness.challenge.coins.CoinsStakeClient;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsOperationConflictException;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsOutcomeUnknownException;
import io.github.returnaction.fitness.challenge.domain.ChallengeOperationKind;
import io.github.returnaction.fitness.challenge.domain.ChallengeStatus;
import io.github.returnaction.fitness.challenge.domain.ParticipationStatus;
import io.github.returnaction.fitness.challenge.dto.OperationAcceptedResponse;
import io.github.returnaction.fitness.challenge.dto.PublishCompletedResponse;
import io.github.returnaction.fitness.challenge.dto.VersionRequest;
import io.github.returnaction.fitness.challenge.entity.ChallengeEntity;
import io.github.returnaction.fitness.challenge.entity.ChallengeOperationEntity;
import io.github.returnaction.fitness.challenge.entity.ChallengeParticipantEntity;
import io.github.returnaction.fitness.challenge.idempotency.IdempotencyService;
import io.github.returnaction.fitness.challenge.idempotency.RequestFingerprint;
import io.github.returnaction.fitness.challenge.idempotency.StoredHttpResponse;
import io.github.returnaction.fitness.challenge.repository.ChallengeOperationRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeParticipantRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeRepository;
import io.github.returnaction.fitness.challenge.shared.exception.BusinessConflictException;
import io.github.returnaction.fitness.challenge.shared.exception.DomainValidationException;
import io.github.returnaction.fitness.challenge.shared.exception.ResourceNotFoundException;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseReason;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseStakeRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveOutcome;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveStakeRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Публикация черновика: durable PUBLISH, резерв Coins, затем подтверждение
 * участия создателя и переход в OPEN. gRPC-вызов выполняется вне транзакции БД.
 */
@Service
@RequiredArgsConstructor
public class ChallengePublishService {

    private static final String PUBLISH_KIND = "PUBLISH";
    private static final String OPERATIONS_PATH = "/api/v1/challenge-operations/";

    private final ChallengeRepository challenges;
    private final ChallengeParticipantRepository participants;
    private final ChallengeOperationRepository operations;
    private final IdempotencyService idempotency;
    private final CoinsStakeClient coins;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    public PublishResult publish(UUID userId, UUID challengeId, String idempotencyKey, VersionRequest request) {
        UUID key = idempotency.requireKey(idempotencyKey);
        String path = "/api/v1/challenges/" + challengeId + "/publish";
        byte[] fingerprint = RequestFingerprint.sha256(
                objectMapper.writeValueAsBytes(Map.of("version", request.version())));

        var replay = idempotency.findReplay(userId, "POST", path, key, fingerprint);
        if (replay.isPresent()) {
            return readStored(replay.get());
        }

        Prepared prepared = prepare(userId, challengeId, request.version());
        if (prepared.pendingOperationId() != null) {
            return accepted(prepared.pendingOperationId());
        }

        ReserveStakeRequest reserve = ReserveStakeRequest.newBuilder()
                .setJoinOperationId(prepared.operationId().toString())
                .setUserId(userId.toString())
                .setChallengeId(challengeId.toString())
                .setAmount(prepared.amount())
                .build();

        try {
            var response = coins.reserveStake(reserve);
            CoinsReserveRejection.throwIfRejected(response.getOutcome(),
                    code -> fail(prepared.operationId(), code));
            if (response.getOutcome() != ReserveOutcome.RESERVE_OUTCOME_RESERVED) {
                throw new IllegalStateException("Unsupported reserve outcome");
            }
            UUID holdId = UUID.fromString(response.getHoldId());
            String failure = confirm(userId, challengeId, prepared.operationId(), holdId);
            if (failure != null) {
                throw new BusinessConflictException(failure, "Challenge is not editable.");
            }
            var body = new PublishCompletedResponse(challengeId, ChallengeStatus.OPEN,
                    ParticipationStatus.JOINED);
            idempotency.store(userId, "POST", path, key, fingerprint,
                    new StoredHttpResponse(200, json(body), "{}"));
            return new PublishResult(200, body);
        } catch (CoinsOutcomeUnknownException exception) {
            return accepted(prepared.operationId());
        } catch (CoinsOperationConflictException exception) {
            throw new BusinessConflictException("stake_conflict", "Coins operation conflicts with previous request.");
        }
    }

    private Prepared prepare(UUID userId, UUID challengeId, long expectedVersion) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ChallengeEntity challenge = challenges.findByIdForUpdate(challengeId)
                    .orElseThrow(ChallengePublishService::challengeNotFound);
            if (!challenge.getCreatorUserId().equals(userId)) {
                throw challengeNotFound();
            }
            if (challenge.getStatus() != ChallengeStatus.DRAFT) {
                throw new BusinessConflictException("challenge_not_editable", "Challenge is not editable.");
            }
            if (challenge.getVersion() != expectedVersion) {
                throw new BusinessConflictException("version_conflict", "Challenge version has changed.");
            }
            validateForPublish(challenge);

            var active = operations.findFirstActive(challengeId, userId);
            if (active.isPresent()) {
                return new Prepared(null, 0, active.get().getId());
            }
            ChallengeOperationEntity operation = operations.save(new ChallengeOperationEntity(
                    challengeId, userId, ChallengeOperationKind.PUBLISH, Instant.now()));
            return new Prepared(operation.getId(),
                    Objects.requireNonNull(challenge.getStakeAmount(), "stakeAmount"), null);
        });
    }

    private String confirm(UUID userId, UUID challengeId, UUID operationId, UUID holdId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ChallengeEntity challenge = challenges.findByIdForUpdate(challengeId)
                    .orElseThrow(ChallengePublishService::challengeNotFound);
            if (!challenge.getCreatorUserId().equals(userId)) {
                return "challenge_not_editable";
            }
            if (challenge.getStatus() != ChallengeStatus.DRAFT) {
                if (participants.findByChallengeIdAndUserId(challengeId, userId).isPresent()) {
                    operations.findById(operationId)
                            .orElseThrow(ChallengePublishService::operationNotFound)
                            .confirm(holdId, Instant.now());
                    return null;
                }
                return "challenge_not_editable";
            }
            Instant now = Instant.now();
            long sequence = challenge.openForJoining(now);
            participants.save(new ChallengeParticipantEntity(challengeId, userId, sequence, now));
            operations.findById(operationId)
                    .orElseThrow(ChallengePublishService::operationNotFound)
                    .confirm(holdId, now);
            return null;
        });
    }

    /**
     * Восстановление ранее начатой PUBLISH-операции после неизвестного ответа.
     */
    void recover(UUID userId, UUID challengeId, UUID operationId, UUID holdId) {
        String failure = confirm(userId, challengeId, operationId, holdId);
        if (failure == null) {
            return;
        }
        try {
            coins.releaseStake(ReleaseStakeRequest.newBuilder()
                    .setReleaseOperationId(UUID.randomUUID().toString())
                    .setHoldId(holdId.toString())
                    .setChallengeId(challengeId.toString())
                    .setReason(ReleaseReason.RELEASE_REASON_JOIN_NOT_CONFIRMED)
                    .build());
        } catch (CoinsOutcomeUnknownException exception) {
            return;
        }
        fail(operationId, failure);
    }

    private void fail(UUID operationId, String failureCode) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> operations.findById(operationId)
                .orElseThrow(ChallengePublishService::operationNotFound)
                .fail(failureCode, Instant.now()));
    }

    private void validateForPublish(ChallengeEntity challenge) {
        List<DomainValidationException.Detail> details = new ArrayList<>();
        if (challenge.getTitle() == null || challenge.getTitle().isBlank()) {
            details.add(new DomainValidationException.Detail("title", "required"));
        }
        if (challenge.getStakeAmount() == null) {
            details.add(new DomainValidationException.Detail("stakeAmount", "required"));
        }
        if (challenge.getDurationDays() == null) {
            details.add(new DomainValidationException.Detail("durationDays", "required"));
        }
        if (!details.isEmpty()) {
            throw new DomainValidationException(details);
        }
    }

    private PublishResult accepted(UUID operationId) {
        var body = new OperationAcceptedResponse(operationId, "PENDING", OPERATIONS_PATH + operationId);
        return new PublishResult(202, body);
    }

    private PublishResult readStored(StoredHttpResponse stored) {
        if (stored.status() == 202) {
            return new PublishResult(202, objectMapper.readValue(stored.body(), OperationAcceptedResponse.class));
        }
        return new PublishResult(stored.status(),
                objectMapper.readValue(stored.body(), PublishCompletedResponse.class));
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

    public record PublishResult(int status, Object body) {
    }

    private record Prepared(UUID operationId, long amount, UUID pendingOperationId) {
    }
}
