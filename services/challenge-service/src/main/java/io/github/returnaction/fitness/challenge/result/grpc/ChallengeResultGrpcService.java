package io.github.returnaction.fitness.challenge.result.grpc;

import io.github.returnaction.fitness.challenge.service.ChallengeResultService;
import io.github.returnaction.fitness.challenge.service.FinalResult;
import io.github.returnaction.fitness.challenge.service.FinalizeResultCommand;
import io.github.returnaction.fitness.challenge.service.ResultWinnersPage;
import io.github.returnaction.fitness.contracts.challenge.results.v1.ChallengeResultServiceGrpc;
import io.github.returnaction.fitness.contracts.challenge.results.v1.FinalizeResultRequest;
import io.github.returnaction.fitness.contracts.challenge.results.v1.FinalizeResultResponse;
import io.github.returnaction.fitness.contracts.challenge.results.v1.ListResultWinnersRequest;
import io.github.returnaction.fitness.contracts.challenge.results.v1.ListResultWinnersResponse;
import io.github.returnaction.fitness.contracts.challenge.results.v1.ResultOutcome;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Внутренний gRPC результата. Авторизация SAN выполняется interceptor.
 */
@Service
@RequiredArgsConstructor
public class ChallengeResultGrpcService extends ChallengeResultServiceGrpc.ChallengeResultServiceImplBase {

    private final ChallengeResultService results;

    @Override
    public void finalizeResult(FinalizeResultRequest request,
                               StreamObserver<FinalizeResultResponse> observer) {
        handle(observer, () -> {
            FinalResult result = results.finalizeResult(new FinalizeResultCommand(
                    UUID.fromString(request.getResultId()),
                    UUID.fromString(request.getChallengeId()),
                    request.getSetVersion(),
                    request.getSetDigest(),
                    outcome(request.getOutcome()),
                    request.getRuleVersion(),
                    request.getWinnerUserIdsList().stream().map(UUID::fromString).toList()));
            return FinalizeResultResponse.newBuilder()
                    .setResultId(result.resultId().toString())
                    .setChallengeId(result.challengeId().toString())
                    .setSetVersion(result.setVersion())
                    .setOutcome(protoOutcome(result.outcome()))
                    .setWinnerCount(result.winnerCount())
                    .setWinnersDigest(result.winnersDigestHex())
                    .build();
        });
    }

    @Override
    public void listResultWinners(ListResultWinnersRequest request,
                                  StreamObserver<ListResultWinnersResponse> observer) {
        handle(observer, () -> {
            ResultWinnersPage page = results.listWinners(UUID.fromString(request.getResultId()),
                    request.getCursor(), request.getLimit());
            return ListResultWinnersResponse.newBuilder()
                    .addAllUserIds(page.userIds().stream().map(UUID::toString).toList())
                    .setNextCursor(page.nextCursor())
                    .build();
        });
    }

    private String outcome(ResultOutcome outcome) {
        return switch (outcome) {
            case RESULT_OUTCOME_SINGLE_WINNER -> "SINGLE_WINNER";
            case RESULT_OUTCOME_DRAW -> "DRAW";
            case RESULT_OUTCOME_MULTIPLE_WINNERS -> "MULTIPLE_WINNERS";
            default -> throw new IllegalArgumentException("Unsupported outcome");
        };
    }

    private ResultOutcome protoOutcome(String outcome) {
        return switch (outcome) {
            case "SINGLE_WINNER" -> ResultOutcome.RESULT_OUTCOME_SINGLE_WINNER;
            case "DRAW" -> ResultOutcome.RESULT_OUTCOME_DRAW;
            case "MULTIPLE_WINNERS" -> ResultOutcome.RESULT_OUTCOME_MULTIPLE_WINNERS;
            default -> ResultOutcome.RESULT_OUTCOME_UNSPECIFIED;
        };
    }

    private <T> void handle(StreamObserver<T> observer, Supplier<T> action) {
        try {
            observer.onNext(action.get());
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(ChallengeResultGrpcErrors.map(exception));
        }
    }
}
