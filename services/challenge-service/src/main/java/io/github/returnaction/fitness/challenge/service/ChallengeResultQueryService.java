package io.github.returnaction.fitness.challenge.service;

import io.github.returnaction.fitness.challenge.domain.ChallengeStatus;
import io.github.returnaction.fitness.challenge.dto.ChallengeResultResponse;
import io.github.returnaction.fitness.challenge.entity.ChallengeEntity;
import io.github.returnaction.fitness.challenge.repository.ChallengeReadRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeRepository;
import io.github.returnaction.fitness.challenge.shared.exception.ResourceNotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Читает окончательный результат challenge и персональный исход viewer.
 */
@Service
@RequiredArgsConstructor
public class ChallengeResultQueryService {

    private static final String DRAW = "DRAW";

    private final ChallengeRepository challenges;
    private final ChallengeReadRepository reads;

    @Transactional(readOnly = true)
    public ChallengeResultResponse getResult(UUID userId, UUID challengeId) {
        ChallengeEntity challenge = challenges.findById(challengeId)
                .orElseThrow(ChallengeResultQueryService::challengeNotFound);
        if (challenge.getStatus() == ChallengeStatus.DRAFT
                && !challenge.getCreatorUserId().equals(userId)) {
            throw challengeNotFound();
        }
        if (challenge.getStatus() == ChallengeStatus.CANCELLED) {
            return new ChallengeResultResponse(challengeId, ChallengeResultResponse.Status.CANCELLED, null);
        }

        return reads.findResult(challengeId, userId)
                .map(result -> new ChallengeResultResponse(challengeId,
                        ChallengeResultResponse.Status.FINAL,
                        new ChallengeResultResponse.FinalResult(
                                result.resultId(),
                                result.outcome(),
                                result.finalizedAt(),
                                viewerOutcome(result),
                                result.winnersCount())))
                .orElseGet(() -> new ChallengeResultResponse(challengeId,
                        ChallengeResultResponse.Status.PENDING, null));
    }

    private String viewerOutcome(ChallengeReadRepository.ResultProjection result) {
        if (!result.viewerParticipant()) {
            return null;
        }
        if (DRAW.equals(result.outcome())) {
            return DRAW;
        }
        return result.viewerWinner() ? "WON" : "LOST";
    }

    private static ResourceNotFoundException challengeNotFound() {
        return new ResourceNotFoundException("challenge_not_found", "Challenge not found.");
    }
}
