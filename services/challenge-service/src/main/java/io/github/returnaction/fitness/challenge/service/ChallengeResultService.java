package io.github.returnaction.fitness.challenge.service;

import io.github.returnaction.fitness.challenge.domain.ChallengeStatus;
import io.github.returnaction.fitness.challenge.entity.ChallengeEntity;
import io.github.returnaction.fitness.challenge.entity.ChallengeParticipantEntity;
import io.github.returnaction.fitness.challenge.outbox.ChallengeOutboxService;
import io.github.returnaction.fitness.challenge.repository.ChallengeParticipantRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeResultRepository;
import io.github.returnaction.fitness.challenge.repository.ChallengeSealRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Единственная фиксация итога challenge: result, winners, digest, статусы
 * участия, FINISHED и outbox — одной локальной транзакцией.
 */
@Service
@RequiredArgsConstructor
public class ChallengeResultService {

    private static final String SINGLE_WINNER = "SINGLE_WINNER";
    private static final String DRAW = "DRAW";
    private static final String MULTIPLE_WINNERS = "MULTIPLE_WINNERS";

    private final ChallengeRepository challenges;
    private final ChallengeParticipantRepository participants;
    private final ChallengeSealRepository sealRepository;
    private final ChallengeResultRepository results;
    private final ChallengeOutboxService outbox;
    private final ObjectMapper objectMapper;

    @Transactional
    public FinalResult finalizeResult(FinalizeResultCommand command) {
        ChallengeEntity challenge = lockChallenge(command.challengeId());
        var existing = results.findByResultId(command.resultId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), command);
        }
        requireNoResultForChallenge(command.challengeId());
        requireActive(challenge);
        requireSealedStakeSet(command);

        ValidatedResult validated = validateResult(command);
        List<ChallengeParticipantEntity> participantRows = requireParticipants(command.challengeId(),
                validated.winners());
        byte[] winnersDigest = ResultDigest.winnersDigest(command.winnerUserIds());
        String winnersDigestHex = ResultDigest.toHex(winnersDigest);

        persistResult(command, validated, winnersDigest);
        finalizeParticipants(participantRows, validated);
        challenge.finish(Instant.now());
        appendFinishedEvent(command, winnersDigestHex);

        return new FinalResult(command.resultId(), command.challengeId(), command.setVersion(),
                validated.outcome(), validated.expectedWinners(), winnersDigestHex);
    }

    private ChallengeEntity lockChallenge(UUID challengeId) {
        return challenges.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new ResultFailureException(
                        ResultFailureException.Reason.CHALLENGE_NOT_ACTIVE));
    }

    private FinalResult replayOrConflict(ChallengeResultRepository.ResultRow existing,
                                         FinalizeResultCommand command) {
        if (matches(existing, command)) {
            return toResult(existing);
        }
        throw new ResultFailureException(ResultFailureException.Reason.RESULT_ID_CONFLICT);
    }

    private void requireNoResultForChallenge(UUID challengeId) {
        if (results.findByChallengeId(challengeId).isPresent()) {
            throw new ResultFailureException(ResultFailureException.Reason.CHALLENGE_RESULT_EXISTS);
        }
    }

    private void requireActive(ChallengeEntity challenge) {
        if (challenge.getStatus() != ChallengeStatus.ACTIVE) {
            throw new ResultFailureException(ResultFailureException.Reason.CHALLENGE_NOT_ACTIVE);
        }
    }

    private void requireSealedStakeSet(FinalizeResultCommand command) {
        var stakeSet = sealRepository.findByChallengeId(command.challengeId())
                .orElseThrow(() -> new ResultFailureException(
                        ResultFailureException.Reason.STAKE_SET_NOT_SEALED));
        if (!"SEALED".equals(stakeSet.state())) {
            throw new ResultFailureException(ResultFailureException.Reason.STAKE_SET_NOT_SEALED);
        }
        if (stakeSet.version() != command.setVersion() || !stakeSet.digestHex().equals(command.setDigest())) {
            throw new ResultFailureException(ResultFailureException.Reason.STAKE_SET_MISMATCH);
        }
    }

    private ValidatedResult validateResult(FinalizeResultCommand command) {
        String outcome = command.outcome();
        if (MULTIPLE_WINNERS.equals(outcome)) {
            throw new ResultFailureException(ResultFailureException.Reason.UNSUPPORTED_OUTCOME);
        }
        int expectedWinners = expectedWinners(outcome);
        if (command.winnerUserIds().size() != expectedWinners) {
            throw new ResultFailureException(ResultFailureException.Reason.WINNER_COUNT_MISMATCH);
        }
        Set<UUID> uniqueWinners = new HashSet<>(command.winnerUserIds());
        if (uniqueWinners.size() != expectedWinners) {
            throw new ResultFailureException(ResultFailureException.Reason.INVALID_REQUEST);
        }
        return new ValidatedResult(outcome, expectedWinners, uniqueWinners);
    }

    private int expectedWinners(String outcome) {
        return switch (outcome) {
            case SINGLE_WINNER -> 1;
            case DRAW -> 2;
            default -> throw new ResultFailureException(ResultFailureException.Reason.INVALID_REQUEST);
        };
    }

    private List<ChallengeParticipantEntity> requireParticipants(UUID challengeId, Set<UUID> winners) {
        List<ChallengeParticipantEntity> participantRows =
                participants.findByChallengeIdOrderByJoinSequenceAsc(challengeId);
        Set<UUID> participantIds = new HashSet<>();
        participantRows.forEach(participant -> participantIds.add(participant.getUserId()));
        if (!participantIds.containsAll(winners)) {
            throw new ResultFailureException(ResultFailureException.Reason.WINNER_NOT_PARTICIPANT);
        }
        return participantRows;
    }

    private void persistResult(FinalizeResultCommand command, ValidatedResult validated, byte[] winnersDigest) {
        results.insertResult(command.resultId(), command.challengeId(), command.setVersion(),
                validated.outcome(), command.ruleVersion(), validated.expectedWinners(), winnersDigest);
        for (UUID winner : command.winnerUserIds()) {
            results.insertWinner(command.resultId(), winner);
        }
    }

    private void finalizeParticipants(List<ChallengeParticipantEntity> participants,
                                      ValidatedResult validated) {
        Instant now = Instant.now();
        for (ChallengeParticipantEntity participant : participants) {
            boolean winner = validated.winners().contains(participant.getUserId());
            if (!DRAW.equals(validated.outcome()) && winner) {
                participant.win(now);
            } else {
                participant.complete(now);
            }
        }
    }

    private void appendFinishedEvent(FinalizeResultCommand command, String winnersDigestHex) {
        UUID eventId = UUID.randomUUID();
        outbox.append(eventId, "challenge.finished", 1, command.challengeId(),
                eventPayload(eventId, command, winnersDigestHex));
    }

    @Transactional(readOnly = true)
    public ResultWinnersPage listWinners(UUID resultId, String cursor, int limit) {
        results.findByResultId(resultId)
                .orElseThrow(() -> new ResultFailureException(ResultFailureException.Reason.RESULT_NOT_FOUND));
        int pageSize = limit == 0 ? 100 : limit;
        if (pageSize < 1 || pageSize > 500) {
            throw new ResultFailureException(ResultFailureException.Reason.INVALID_REQUEST);
        }
        UUID after = parseCursor(cursor);
        List<UUID> page = results.findWinnerPage(resultId, after, pageSize + 1);
        boolean hasMore = page.size() > pageSize;
        List<UUID> userIds = hasMore ? page.subList(0, pageSize) : page;
        String next = hasMore ? userIds.getLast().toString() : "";
        return new ResultWinnersPage(List.copyOf(userIds), next);
    }

    private UUID parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(cursor);
        } catch (IllegalArgumentException _) {
            throw new ResultFailureException(ResultFailureException.Reason.INVALID_REQUEST);
        }
    }

    private boolean matches(ChallengeResultRepository.ResultRow row, FinalizeResultCommand command) {
        return row.challengeId().equals(command.challengeId())
                && row.setVersion() == command.setVersion()
                && row.outcome().equals(command.outcome())
                && row.ruleVersion().equals(command.ruleVersion())
                && row.winnerCount() == command.winnerUserIds().size()
                && row.winnersDigestHex().equals(
                        ResultDigest.toHex(ResultDigest.winnersDigest(command.winnerUserIds())));
    }

    private FinalResult toResult(ChallengeResultRepository.ResultRow row) {
        return new FinalResult(row.id(), row.challengeId(), row.setVersion(), row.outcome(),
                row.winnerCount(), row.winnersDigestHex());
    }

    private record ValidatedResult(String outcome, int expectedWinners, Set<UUID> winners) {
    }

    private String eventPayload(UUID eventId, FinalizeResultCommand command, String winnersDigestHex) {        var data = new LinkedHashMap<String, Object>();
        data.put("challengeId", command.challengeId().toString());
        data.put("resultId", command.resultId().toString());
        data.put("setVersion", command.setVersion());
        data.put("setDigest", command.setDigest());
        data.put("ruleVersion", command.ruleVersion());
        data.put("outcome", command.outcome());
        data.put("winnerCount", command.winnerUserIds().size());
        data.put("winnersDigest", winnersDigestHex);

        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("event_id", eventId.toString());
        envelope.put("event_type", "challenge.finished");
        envelope.put("schema_version", 1);
        envelope.put("occurred_at", Instant.now().toString());
        envelope.put("producer", "challenge");
        envelope.put("correlation_id", command.resultId().toString());
        envelope.put("data", data);
        return objectMapper.writeValueAsString(envelope);
    }
}
