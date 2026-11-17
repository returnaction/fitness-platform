package io.github.returnaction.fitness.challenge.service;

import io.github.returnaction.fitness.challenge.domain.ChallengeMode;
import io.github.returnaction.fitness.challenge.domain.ChallengeOperationStatus;
import io.github.returnaction.fitness.challenge.domain.ChallengeStatus;
import io.github.returnaction.fitness.challenge.domain.DraftChanges;
import io.github.returnaction.fitness.challenge.domain.ParticipationStatus;
import io.github.returnaction.fitness.challenge.domain.RefundStatus;
import io.github.returnaction.fitness.challenge.domain.ReleaseIntentStatus;
import io.github.returnaction.fitness.challenge.domain.StartMode;
import io.github.returnaction.fitness.challenge.dto.ChallengeDetailsResponse;
import io.github.returnaction.fitness.challenge.dto.DraftResponse;
import io.github.returnaction.fitness.challenge.dto.OperationResponse;
import io.github.returnaction.fitness.challenge.dto.UpdateDraftRequest;
import io.github.returnaction.fitness.challenge.entity.ChallengeEntity;
import io.github.returnaction.fitness.challenge.entity.ChallengeParticipantEntity;
import io.github.returnaction.fitness.challenge.idempotency.IdempotencyService;
import io.github.returnaction.fitness.challenge.idempotency.RequestFingerprint;
import io.github.returnaction.fitness.challenge.idempotency.StoredHttpResponse;
import io.github.returnaction.fitness.challenge.repository.ChallengeOperationRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeParticipantRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeReadRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeReleaseIntentRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeRepository;
import io.github.returnaction.fitness.challenge.shared.exception.BusinessConflictException;
import io.github.returnaction.fitness.challenge.shared.exception.ResourceNotFoundException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ChallengeDraftService {

    private static final String DRAFTS_PATH = "/api/v1/challenges/drafts";
    private static final int MIN_PARTICIPANTS = 2;
    private static final int MAX_PARTICIPANTS = 2;

    private final ChallengeRepository challenges;
    private final ChallengeParticipantRepository participants;
    private final ChallengeOperationRepository operations;
    private final ChallengeReleaseIntentRepository releaseIntents;
    private final ChallengeReadRepository reads;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    @Transactional
    public DraftResponse createDraft(UUID userId, String idempotencyKey) {
        UUID key = idempotency.requireKey(idempotencyKey);
        byte[] fingerprint = RequestFingerprint.sha256("{}");

        var replay = idempotency.findReplay(userId, "POST", DRAFTS_PATH, key, fingerprint);
        if (replay.isPresent()) {
            return readDraft(replay.get());
        }

        ChallengeEntity challenge = challenges.save(new ChallengeEntity(userId, Instant.now()));
        DraftResponse response = new DraftResponse(challenge.getId(), challenge.getStatus(), challenge.getVersion());
        idempotency.store(userId, "POST", DRAFTS_PATH, key, fingerprint,
                new StoredHttpResponse(201, json(response), "{}"));
        return response;
    }

    @Transactional
    public DraftResponse updateDraft(UUID userId, UUID challengeId, String idempotencyKey,
                                     UpdateDraftRequest request) {
        UUID key = idempotency.requireKey(idempotencyKey);
        String path = "/api/v1/challenges/" + challengeId + "/draft";
        byte[] fingerprint = canonical(request);

        var replay = idempotency.findReplay(userId, "PATCH", path, key, fingerprint);
        if (replay.isPresent()) {
            return readDraft(replay.get());
        }

        ChallengeEntity challenge = challenges.findByIdForUpdate(challengeId).orElseThrow(
                ChallengeDraftService::challengeNotFound);
        if (!challenge.getCreatorUserId().equals(userId)) {
            throw challengeNotFound();
        }
        if (challenge.getStatus() != ChallengeStatus.DRAFT) {
            throw new BusinessConflictException("challenge_not_editable", "Challenge is not editable.");
        }
        if (challenge.getVersion() != request.getVersion()) {
            throw new BusinessConflictException("version_conflict", "Challenge version has changed.");
        }

        challenge.updateDraft(new DraftChanges(
                request.getTitle(), request.isTitlePresent(),
                request.getDescription(), request.isDescriptionPresent(),
                request.getStakeAmount(), request.isStakeAmountPresent(),
                request.getDurationDays(), request.isDurationDaysPresent()), Instant.now());

        DraftResponse response = new DraftResponse(challenge.getId(), challenge.getStatus(), challenge.getVersion());
        idempotency.store(userId, "PATCH", path, key, fingerprint,
                new StoredHttpResponse(200, json(response), "{}"));
        return response;
    }

    @Transactional(readOnly = true)
    public ChallengeDetailsResponse getChallenge(UUID userId, UUID challengeId) {
        ChallengeEntity challenge = challenges.findById(challengeId).orElseThrow(
                ChallengeDraftService::challengeNotFound);
        if (challenge.getStatus() == ChallengeStatus.DRAFT && !challenge.getCreatorUserId().equals(userId)) {
            throw challengeNotFound();
        }

        ParticipationStatus viewerStatus = participants.findByChallengeIdAndUserId(challengeId, userId)
                .map(ChallengeParticipantEntity::getStatus)
                .orElse(null);

        return new ChallengeDetailsResponse(
                challenge.getId(),
                challenge.getTitle(),
                challenge.getDescription(),
                challenge.getStatus(),
                ChallengeMode.HEAD_TO_HEAD,
                challenge.getVisibility(),
                challenge.getStakeAmount(),
                new ChallengeDetailsResponse.Participants(MIN_PARTICIPANTS, MAX_PARTICIPANTS,
                        participants.countByChallengeId(challengeId)),
                new ChallengeDetailsResponse.Start(StartMode.WHEN_FULL, null, challenge.getStartedAt()),
                challenge.getDurationDays(),
                new ChallengeDetailsResponse.Viewer(viewerStatus != null,
                        challenge.getCreatorUserId().equals(userId), viewerStatus),
                refundStatus(challenge),
                reads.settlementStatus(challengeId),
                challenge.getVersion(),
                challenge.getCreatedAt(),
                challenge.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public OperationResponse getOperation(UUID userId, UUID operationId) {
        var operation = operations.findById(operationId).orElseThrow(ChallengeDraftService::operationNotFound);
        if (!operation.getUserId().equals(userId)) {
            throw operationNotFound();
        }

        OperationResponse.OperationStatus status = switch (operation.getStatus()) {
            case PENDING, RESERVED, RELEASING -> OperationResponse.OperationStatus.PENDING;
            case CONFIRMED -> OperationResponse.OperationStatus.SUCCEEDED;
            case FAILED -> OperationResponse.OperationStatus.FAILED;
        };
        Object result = null;
        if (operation.getStatus() == ChallengeOperationStatus.FAILED) {
            String failureCode = Objects.requireNonNullElse(operation.getFailureCode(), "operation_failed");
            result = new OperationResponse.OperationFailure(failureCode);
        }

        return new OperationResponse(operation.getId(), operation.getKind(), status,
                operation.getChallengeId(), result);
    }

    private RefundStatus refundStatus(ChallengeEntity challenge) {
        if (challenge.getStatus() != ChallengeStatus.CANCELLED) {
            return RefundStatus.NONE;
        }
        boolean pending = !releaseIntents
                .findByChallengeIdAndStatus(challenge.getId(), ReleaseIntentStatus.PENDING)
                .isEmpty();
        return pending ? RefundStatus.PENDING : RefundStatus.COMPLETED;
    }

    private DraftResponse readDraft(StoredHttpResponse stored) {
        return objectMapper.readValue(stored.body(), DraftResponse.class);
    }

    private byte[] canonical(UpdateDraftRequest request) {
        var values = new LinkedHashMap<String, Object>();
        values.put("version", request.getVersion());
        if (request.isTitlePresent()) {
            values.put("title", request.getTitle());
        }
        if (request.isDescriptionPresent()) {
            values.put("description", request.getDescription());
        }
        if (request.isStakeAmountPresent()) {
            values.put("stakeAmount", request.getStakeAmount());
        }
        if (request.isDurationDaysPresent()) {
            values.put("durationDays", request.getDurationDays());
        }
        return RequestFingerprint.sha256(objectMapper.writeValueAsBytes(values));
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private static ResourceNotFoundException challengeNotFound() {
        return new ResourceNotFoundException("challenge_not_found", "Challenge not found.");
    }

    private static ResourceNotFoundException operationNotFound() {
        return new ResourceNotFoundException("operation_not_found", "Operation not found.");
    }
}
