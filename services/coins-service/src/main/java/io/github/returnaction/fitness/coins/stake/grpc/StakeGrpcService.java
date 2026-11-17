package io.github.returnaction.fitness.coins.stake.grpc;

import static io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseOutcome.RELEASE_OUTCOME_RELEASED;
import static io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveOutcome.RESERVE_OUTCOME_ACTIVE_HOLD_EXISTS;
import static io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveOutcome.RESERVE_OUTCOME_INSUFFICIENT_FUNDS;
import static io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveOutcome.RESERVE_OUTCOME_RESERVED;

import io.github.returnaction.fitness.coins.stake.BeginStakeSetCommand;
import io.github.returnaction.fitness.coins.stake.CompleteStakeCancellationCommand;
import io.github.returnaction.fitness.coins.stake.FinalizeStakeSetCommand;
import io.github.returnaction.fitness.coins.stake.FreezeStakeSetCommand;
import io.github.returnaction.fitness.coins.stake.PutStakeBatchCommand;
import io.github.returnaction.fitness.coins.stake.ReleaseReason;
import io.github.returnaction.fitness.coins.stake.ReleaseStakeCommand;
import io.github.returnaction.fitness.coins.stake.ReleaseStakeService;
import io.github.returnaction.fitness.coins.stake.ReleaseStakeResult;
import io.github.returnaction.fitness.coins.stake.ReserveStakeCommand;
import io.github.returnaction.fitness.coins.stake.ReserveStakeResult;
import io.github.returnaction.fitness.coins.stake.SealedStake;
import io.github.returnaction.fitness.coins.stake.StakeCancellationService;
import io.github.returnaction.fitness.coins.stake.StakeOperationQueryService;
import io.github.returnaction.fitness.coins.stake.StakeReservationService;
import io.github.returnaction.fitness.coins.stake.StakeSetService;
import io.github.returnaction.fitness.contracts.coins.stake.v1.BeginStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.BeginStakeSetResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.CompleteStakeCancellationRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.CompleteStakeCancellationResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FinalizeStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FinalizeStakeSetResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FreezeStakeSetRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.FreezeStakeSetResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.GetStakeOperationRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.GetStakeOperationResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.PutStakeBatchRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.PutStakeBatchResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseStakeRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveStakeRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveStakeResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.StakeOperationKind;
import io.github.returnaction.fitness.contracts.coins.stake.v1.StakeServiceGrpc;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * gRPC-фасад stake-команд Coins. Авторизация сервиса Challenge выполняется
 * отдельным interceptor; здесь только маппинг контракта и ошибок.
 */
@Service
@RequiredArgsConstructor
public class StakeGrpcService extends StakeServiceGrpc.StakeServiceImplBase {

    private final StakeReservationService reservations;
    private final ReleaseStakeService releases;
    private final StakeOperationQueryService queries;
    private final StakeSetService stakeSets;
    private final StakeCancellationService cancellations;

    @Override
    public void reserveStake(ReserveStakeRequest request, StreamObserver<ReserveStakeResponse> observer) {
        handle(observer, () -> reserveResponse(reservations.reserve(new ReserveStakeCommand(
                UUID.fromString(request.getJoinOperationId()),
                UUID.fromString(request.getUserId()),
                UUID.fromString(request.getChallengeId()),
                request.getAmount()))));
    }

    @Override
    public void releaseStake(ReleaseStakeRequest request,
                             StreamObserver<io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseStakeResponse> observer) {
        handle(observer, () -> releaseResponse(releases.release(new ReleaseStakeCommand(
                UUID.fromString(request.getReleaseOperationId()),
                UUID.fromString(request.getHoldId()),
                UUID.fromString(request.getChallengeId()),
                releaseReason(request)))));
    }

    @Override
    public void getStakeOperation(GetStakeOperationRequest request,
                                  StreamObserver<GetStakeOperationResponse> observer) {
        handle(observer, () -> operationResponse(request));
    }

    @Override
    public void beginStakeSet(BeginStakeSetRequest request, StreamObserver<BeginStakeSetResponse> observer) {
        handle(observer, () -> BeginStakeSetResponse.newBuilder()
                .setSealOperationId(stakeSets.begin(new BeginStakeSetCommand(
                        UUID.fromString(request.getSealOperationId()),
                        UUID.fromString(request.getChallengeId()),
                        request.getSetVersion(),
                        request.getExpectedCount(),
                        request.getExpectedDigest())).sealOperationId().toString())
                .build());
    }

    @Override
    public void putStakeBatch(PutStakeBatchRequest request, StreamObserver<PutStakeBatchResponse> observer) {
        handle(observer, () -> {
            List<SealedStake> stakes = request.getStakesList().stream()
                    .map(stake -> new SealedStake(
                            UUID.fromString(stake.getHoldId()),
                            UUID.fromString(stake.getUserId()),
                            stake.getAmount()))
                    .toList();
            var result = stakeSets.putBatch(new PutStakeBatchCommand(
                    UUID.fromString(request.getSealOperationId()), request.getBatchNumber(), stakes));
            return PutStakeBatchResponse.newBuilder()
                    .setSealOperationId(result.sealOperationId().toString())
                    .setBatchNumber(result.batchNumber())
                    .build();
        });
    }

    @Override
    public void finalizeStakeSet(FinalizeStakeSetRequest request,
                                 StreamObserver<FinalizeStakeSetResponse> observer) {
        handle(observer, () -> {
            var result = stakeSets.finalizeSet(new FinalizeStakeSetCommand(
                    UUID.fromString(request.getSealOperationId())));
            return FinalizeStakeSetResponse.newBuilder()
                    .setSealOperationId(result.sealOperationId().toString())
                    .setChallengeId(result.challengeId().toString())
                    .setSetVersion(result.setVersion())
                    .setStakeCount(result.stakeCount())
                    .setSetDigest(result.setDigestHex())
                    .build();
        });
    }

    @Override
    public void freezeStakeSet(FreezeStakeSetRequest request, StreamObserver<FreezeStakeSetResponse> observer) {
        handle(observer, () -> {
            var result = cancellations.freeze(new FreezeStakeSetCommand(
                    UUID.fromString(request.getCancellationId()), UUID.fromString(request.getChallengeId())));
            return FreezeStakeSetResponse.newBuilder()
                    .setCancellationId(result.cancellationId().toString())
                    .setChallengeId(result.challengeId().toString())
                    .build();
        });
    }

    @Override
    public void completeStakeCancellation(CompleteStakeCancellationRequest request,
                                          StreamObserver<CompleteStakeCancellationResponse> observer) {
        handle(observer, () -> {
            var result = cancellations.complete(
                    new CompleteStakeCancellationCommand(
                            UUID.fromString(request.getCancellationId()),
                            UUID.fromString(request.getChallengeId())));
            return CompleteStakeCancellationResponse.newBuilder()
                    .setCancellationId(result.cancellationId().toString())
                    .setChallengeId(result.challengeId().toString())
                    .build();
        });
    }

    private GetStakeOperationResponse operationResponse(GetStakeOperationRequest request) {
        UUID operationId = UUID.fromString(request.getOperationId());
        if (request.getKind() == StakeOperationKind.STAKE_OPERATION_KIND_RESERVE) {
            return GetStakeOperationResponse.newBuilder()
                    .setReserve(reserveResponse(queries.reserve(operationId)))
                    .build();
        }
        if (request.getKind() == StakeOperationKind.STAKE_OPERATION_KIND_RELEASE) {
            return GetStakeOperationResponse.newBuilder()
                    .setRelease(releaseResponse(queries.release(operationId)))
                    .build();
        }
        throw new IllegalArgumentException("Unsupported operation kind");
    }

    private ReserveStakeResponse reserveResponse(ReserveStakeResult result) {
        var builder = ReserveStakeResponse.newBuilder()
                .setJoinOperationId(result.joinOperationId().toString())
                .setOutcome(switch (result.outcome()) {
                    case RESERVED -> RESERVE_OUTCOME_RESERVED;
                    case INSUFFICIENT_FUNDS -> RESERVE_OUTCOME_INSUFFICIENT_FUNDS;
                    case ACTIVE_HOLD_EXISTS -> RESERVE_OUTCOME_ACTIVE_HOLD_EXISTS;
                });
        if (result.holdId() != null) {
            builder.setHoldId(result.holdId().toString());
        }
        return builder.build();
    }

    private io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseStakeResponse releaseResponse(
            ReleaseStakeResult result) {
        return io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseStakeResponse.newBuilder()
                .setReleaseOperationId(result.releaseOperationId().toString())
                .setHoldId(result.holdId().toString())
                .setOutcome(RELEASE_OUTCOME_RELEASED)
                .build();
    }

    private ReleaseReason releaseReason(ReleaseStakeRequest request) {
        return switch (request.getReason()) {
            case RELEASE_REASON_JOIN_NOT_CONFIRMED -> ReleaseReason.JOIN_NOT_CONFIRMED;
            case RELEASE_REASON_CHALLENGE_CANCELLED -> ReleaseReason.CHALLENGE_CANCELLED;
            default -> throw new IllegalArgumentException("Unsupported release reason");
        };
    }

    private <T> void handle(StreamObserver<T> observer, Supplier<T> action) {
        try {
            observer.onNext(action.get());
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(StakeGrpcErrors.map(exception));
        }
    }
}
