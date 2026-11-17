package io.github.returnaction.fitness.challenge.service;

import io.github.returnaction.fitness.challenge.coins.CoinsReserveRejection;
import io.github.returnaction.fitness.challenge.coins.CoinsStakeClient;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsOperationConflictException;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsOutcomeUnknownException;
import io.github.returnaction.fitness.challenge.domain.ChallengeOperationKind;
import io.github.returnaction.fitness.challenge.domain.ChallengeStatus;
import io.github.returnaction.fitness.challenge.domain.ParticipationStatus;
import io.github.returnaction.fitness.challenge.dto.JoinCompletedResponse;
import io.github.returnaction.fitness.challenge.dto.OperationAcceptedResponse;
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
import io.github.returnaction.fitness.challenge.shared.exception.ResourceNotFoundException;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseReason;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseStakeRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveOutcome;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveStakeRequest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Вступление: сначала резерв Coins, затем повторная проверка места под
 * блокировкой challenge. При неудаче подтверждения hold освобождается.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChallengeJoinService {

    private static final String JOIN_KIND = "JOIN";
    private static final String OPERATIONS_PATH = "/api/v1/challenge-operations/";

    private final ChallengeRepository challenges;
    private final ChallengeParticipantRepository participants;
    private final ChallengeOperationRepository operations;
    private final IdempotencyService idempotency;
    private final CoinsStakeClient coins;
    private final ChallengeSealService sealService;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    public JoinResult join(UUID userId, UUID challengeId, String idempotencyKey) {
        UUID key = idempotency.requireKey(idempotencyKey);
        String path = "/api/v1/challenges/" + challengeId + "/joins";
        byte[] fingerprint = RequestFingerprint.sha256("{}");

        var replay = idempotency.findReplay(userId, "POST", path, key, fingerprint);
        if (replay.isPresent()) {
            return readStored(replay.get());
        }

        Prepared prepared = prepare(userId, challengeId);
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
            markReserved(prepared.operationId(), holdId);
            String failureCode = confirm(userId, challengeId, prepared.operationId(), holdId);
            if (failureCode != null) {
                return compensate(prepared.operationId(), challengeId, holdId, failureCode);
            }
            var body = new JoinCompletedResponse(challengeId, ParticipationStatus.JOINED);
            idempotency.store(userId, "POST", path, key, fingerprint,
                    new StoredHttpResponse(200, json(body), "{}"));
            try {
                sealService.sealIfFull(challengeId);
            } catch (RuntimeException exception) {
                log.warn("Seal deferred to recovery, challenge_id={}", challengeId, exception);
            }
            return new JoinResult(200, body);
        } catch (CoinsOutcomeUnknownException exception) {
            return accepted(prepared.operationId());
        } catch (CoinsOperationConflictException exception) {
            throw new BusinessConflictException("stake_conflict", "Coins operation conflicts with previous request.");
        }
    }

    private Prepared prepare(UUID userId, UUID challengeId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ChallengeEntity challenge = challenges.findByIdForUpdate(challengeId)
                    .orElseThrow(ChallengeJoinService::challengeNotFound);
            requireJoinable(challenge);
            if (participants.findByChallengeIdAndUserId(challengeId, userId).isPresent()) {
                throw new BusinessConflictException("already_joined", "User has already joined.");
            }
            if (participants.countByChallengeId(challengeId) >= maxParticipants(challenge)) {
                throw new BusinessConflictException("challenge_full", "Challenge is full.");
            }

            var active = operations.findFirstActive(challengeId, userId);
            if (active.isPresent()) {
                return new Prepared(null, 0, active.get().getId());
            }
            ChallengeOperationEntity operation = operations.save(new ChallengeOperationEntity(
                    challengeId, userId, ChallengeOperationKind.JOIN, Instant.now()));
            return new Prepared(operation.getId(),
                    Objects.requireNonNull(challenge.getStakeAmount(), "stakeAmount"), null);
        });
    }

    String confirm(UUID userId, UUID challengeId, UUID operationId, UUID holdId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ChallengeEntity challenge = challenges.findByIdForUpdate(challengeId)
                    .orElseThrow(ChallengeJoinService::challengeNotFound);
            if (participants.findByChallengeIdAndUserId(challengeId, userId).isPresent()) {
                return "already_joined";
            }
            if (challenge.getStatus() == ChallengeStatus.CANCELLED) {
                return "challenge_cancelled";
            }
            if (challenge.getStatus() != ChallengeStatus.OPEN) {
                return "challenge_started";
            }
            if (participants.countByChallengeId(challengeId) >= maxParticipants(challenge)) {
                return "challenge_full";
            }

            Instant now = Instant.now();
            long sequence = challenge.takeNextJoinSequence(now);
            participants.save(new ChallengeParticipantEntity(challengeId, userId, sequence, now));
            operations.findById(operationId)
                    .orElseThrow(ChallengeJoinService::operationNotFound)
                    .confirm(holdId, now);
            return null;
        });
    }

    /**
     * Восстановление ранее начатой JOIN-операции после неизвестного ответа.
     */
    void recover(UUID userId, UUID challengeId, UUID operationId, UUID holdId) {
        String failure = confirm(userId, challengeId, operationId, holdId);
        if (failure == null) {
            return;
        }
        try {
            compensate(operationId, challengeId, holdId, failure);
        } catch (BusinessConflictException ignored) {
            // Hold освобождён, операция помечена FAILED.
        }
    }

    private JoinResult compensate(UUID operationId, UUID challengeId, UUID holdId, String failureCode) {
        ReleaseStakeRequest release = ReleaseStakeRequest.newBuilder()
                .setReleaseOperationId(UUID.randomUUID().toString())
                .setHoldId(holdId.toString())
                .setChallengeId(challengeId.toString())
                .setReason(ReleaseReason.RELEASE_REASON_JOIN_NOT_CONFIRMED)
                .build();
        try {
            coins.releaseStake(release);
        } catch (CoinsOutcomeUnknownException exception) {
            return accepted(operationId);
        }
        fail(operationId, failureCode);
        throw conflict(failureCode);
    }

    private void requireJoinable(ChallengeEntity challenge) {
        if (challenge.getStatus() == ChallengeStatus.DRAFT) {
            throw challengeNotFound();
        }
        if (challenge.getStatus() == ChallengeStatus.CANCELLED) {
            throw new BusinessConflictException("challenge_cancelled", "Challenge is cancelled.");
        }
        if (challenge.getStatus() != ChallengeStatus.OPEN) {
            throw new BusinessConflictException("challenge_started", "Challenge has already started.");
        }
    }

    private int maxParticipants(ChallengeEntity challenge) {
        return challenge.getMaxParticipants() == null ? 2 : challenge.getMaxParticipants();
    }

    private void markReserved(UUID operationId, UUID holdId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> operations
                .findById(operationId)
                .orElseThrow(ChallengeJoinService::operationNotFound)
                .markReserved(holdId, Instant.now()));
    }

    private void fail(UUID operationId, String failureCode) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> operations
                .findById(operationId)
                .orElseThrow(ChallengeJoinService::operationNotFound)
                .fail(failureCode, Instant.now()));
    }

    private JoinResult accepted(UUID operationId) {
        var body = new OperationAcceptedResponse(operationId, "PENDING", OPERATIONS_PATH + operationId);
        return new JoinResult(202, body);
    }

    private JoinResult readStored(StoredHttpResponse stored) {
        if (stored.status() == 202) {
            return new JoinResult(202, objectMapper.readValue(stored.body(), OperationAcceptedResponse.class));
        }
        return new JoinResult(stored.status(),
                objectMapper.readValue(stored.body(), JoinCompletedResponse.class));
    }

    private String json(Object body) {
        return objectMapper.writeValueAsString(body);
    }

    private static BusinessConflictException conflict(String code) {
        return switch (code) {
            case "already_joined" -> new BusinessConflictException(code, "User has already joined.");
            case "challenge_full" -> new BusinessConflictException(code, "Challenge is full.");
            case "challenge_cancelled" -> new BusinessConflictException(code, "Challenge is cancelled.");
            case "challenge_started" -> new BusinessConflictException(code, "Challenge has already started.");
            default -> new BusinessConflictException("challenge_full", "Challenge is full.");
        };
    }

    private static ResourceNotFoundException challengeNotFound() {
        return new ResourceNotFoundException("challenge_not_found", "Challenge not found.");
    }

    private static ResourceNotFoundException operationNotFound() {
        return new ResourceNotFoundException("operation_not_found", "Operation not found.");
    }

    public record JoinResult(int status, Object body) {
    }

    private record Prepared(UUID operationId, long amount, UUID pendingOperationId) {
    }
}
