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
        ChallengeEntity challenge = challenges.findByIdForUpdate(command.challengeId())
                .orElseThrow(() -> new ResultFailureException(ResultFailureException.Reason.CHALLENGE_NOT_ACTIVE));

        var existing = results.findByResultId(command.resultId());
        if (existing.isPresent()) {
            if (matches(existing.get(), command)) {
                return toResult(existing.get());
            }
            throw new ResultFailureException(ResultFailureException.Reason.RESULT_ID_CONFLICT);
        }
        if (results.findByChallengeId(command.challengeId()).isPresent()) {
            throw new ResultFailureException(ResultFailureException.Reason.CHALLENGE_RESULT_EXISTS);
        }
        if (challenge.getStatus() != ChallengeStatus.ACTIVE) {
            throw new ResultFailureException(ResultFailureException.Reason.CHALLENGE_NOT_ACTIVE);
        }

        var stakeSet = sealRepository.findByChallengeId(command.challengeId())
                .orElseThrow(() -> new ResultFailureException(
                        ResultFailureException.Reason.STAKE_SET_NOT_SEALED));
        if (!"SEALED".equals(stakeSet.state())) {
            throw new ResultFailureException(ResultFailureException.Reason.STAKE_SET_NOT_SEALED);
        }
        if (stakeSet.version() != command.setVersion() || !stakeSet.digestHex().equals(command.setDigest())) {
            throw new ResultFailureException(ResultFailureException.Reason.STAKE_SET_MISMATCH);
        }

        String outcome = command.outcome();
        if (MULTIPLE_WINNERS.equals(outcome)) {
            throw new ResultFailureException(ResultFailureException.Reason.UNSUPPORTED_OUTCOME);
        }
        if (!SINGLE_WINNER.equals(outcome) && !DRAW.equals(outcome)) {
            throw new ResultFailureException(ResultFailureException.Reason.INVALID_REQUEST);
        }
        int expectedWinners = SINGLE_WINNER.equals(outcome) ? 1 : 2;
        if (command.winnerUserIds().size() != expectedWinners) {
            throw new ResultFailureException(ResultFailureException.Reason.WINNER_COUNT_MISMATCH);
        }
        Set<UUID> unique = new HashSet<>(command.winnerUserIds());
        if (unique.size() != command.winnerUserIds().size()) {
            throw new ResultFailureException(ResultFailureException.Reason.INVALID_REQUEST);
        }

        List<ChallengeParticipantEntity> participantRows =
                participants.findByChallengeIdOrderByJoinSequenceAsc(command.challengeId());
        Set<UUID> participantIds = new HashSet<>();
        participantRows.forEach(participant -> participantIds.add(participant.getUserId()));
        if (!participantIds.containsAll(unique)) {
            throw new ResultFailureException(ResultFailureException.Reason.WINNER_NOT_PARTICIPANT);
        }

        byte[] winnersDigest = ResultDigest.winnersDigest(command.winnerUserIds());
        String winnersDigestHex = ResultDigest.toHex(winnersDigest);
        results.insertResult(command.resultId(), command.challengeId(), command.setVersion(), outcome,
                command.ruleVersion(), expectedWinners, winnersDigest);
        for (UUID winner : command.winnerUserIds()) {
            results.insertWinner(command.resultId(), winner);
        }

        Instant now = Instant.now();
        for (ChallengeParticipantEntity participant : participantRows) {
            if (DRAW.equals(outcome)) {
                participant.complete(now);
            } else if (unique.contains(participant.getUserId())) {
                participant.win(now);
            } else {
                participant.complete(now);
            }
        }
        challenge.finish(now);
        UUID eventId = UUID.randomUUID();
        outbox.append(eventId, "challenge.finished", 1, command.challengeId(),
                eventPayload(eventId, command, winnersDigestHex));

        return new FinalResult(command.resultId(), command.challengeId(), command.setVersion(), outcome,
                expectedWinners, winnersDigestHex);
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
            // TODO(spec-020): заменить UUID-курсор на подписанный HMAC-курсор,
            // когда появится реальная пагинация победителей.
            return UUID.fromString(cursor);
        } catch (IllegalArgumentException exception) {
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

    private String eventPayload(UUID eventId, FinalizeResultCommand command, String winnersDigestHex) {
        var data = new LinkedHashMap<String, Object>();
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
