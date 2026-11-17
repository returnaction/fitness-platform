package io.github.returnaction.fitness.challenge.controller;

import io.github.returnaction.fitness.challenge.dto.CancelRequest;
import io.github.returnaction.fitness.challenge.dto.ChallengeDetailsResponse;
import io.github.returnaction.fitness.challenge.dto.ChallengeResultResponse;
import io.github.returnaction.fitness.challenge.dto.CreateDraftRequest;
import io.github.returnaction.fitness.challenge.dto.DraftResponse;
import io.github.returnaction.fitness.challenge.dto.JoinRequest;
import io.github.returnaction.fitness.challenge.dto.OperationResponse;
import io.github.returnaction.fitness.challenge.dto.UpdateDraftRequest;
import io.github.returnaction.fitness.challenge.dto.VersionRequest;
import io.github.returnaction.fitness.challenge.service.ChallengeDraftService;
import io.github.returnaction.fitness.challenge.service.ChallengeJoinService;
import io.github.returnaction.fitness.challenge.service.ChallengePublishService;
import io.github.returnaction.fitness.challenge.service.ChallengeCancelService;
import io.github.returnaction.fitness.challenge.service.ChallengeResultQueryService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ChallengeController {

    private static final String NO_STORE = "no-store";

    private final ChallengeDraftService service;
    private final ChallengePublishService publishService;
    private final ChallengeJoinService joinService;
    private final ChallengeCancelService cancelService;
    private final ChallengeResultQueryService resultService;

    @PostMapping("/api/v1/challenges/drafts")
    public ResponseEntity<DraftResponse> createDraft(
            @AuthenticationPrincipal UUID userId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateDraftRequest request) {
        DraftResponse response = service.createDraft(userId, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .header(HttpHeaders.LOCATION, "/api/v1/challenges/" + response.id())
                .body(response);
    }

    @PatchMapping("/api/v1/challenges/{challengeId}/draft")
    public ResponseEntity<DraftResponse> updateDraft(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID challengeId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody UpdateDraftRequest request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(service.updateDraft(userId, challengeId, idempotencyKey, request));
    }

    @PostMapping("/api/v1/challenges/{challengeId}/publish")
    public ResponseEntity<?> publish(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID challengeId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VersionRequest request) {
        var result = publishService.publish(userId, challengeId, idempotencyKey, request);
        return ResponseEntity.status(result.status())
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(result.body());
    }

    @PostMapping("/api/v1/challenges/{challengeId}/joins")
    public ResponseEntity<?> join(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID challengeId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody JoinRequest request) {
        var result = joinService.join(userId, challengeId, idempotencyKey);
        return ResponseEntity.status(result.status())
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(result.body());
    }

    @PostMapping("/api/v1/challenges/{challengeId}/cancel")
    public ResponseEntity<?> cancel(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID challengeId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CancelRequest request) {
        var result = cancelService.cancel(userId, challengeId, idempotencyKey);
        return ResponseEntity.status(result.status())
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(result.body());
    }

    @GetMapping("/api/v1/challenges/{challengeId}")
    public ResponseEntity<ChallengeDetailsResponse> getChallenge(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID challengeId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(service.getChallenge(userId, challengeId));
    }

    @GetMapping("/api/v1/challenge-operations/{operationId}")
    public ResponseEntity<OperationResponse> getOperation(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID operationId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(service.getOperation(userId, operationId));
    }

    @GetMapping("/api/v1/challenges/{challengeId}/result")
    public ResponseEntity<ChallengeResultResponse> getResult(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID challengeId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(resultService.getResult(userId, challengeId));
    }
}
