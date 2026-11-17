package io.github.returnaction.fitness.coins.stake.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.rpc.ErrorInfo;
import io.github.returnaction.fitness.coins.stake.ReleaseStakeResult;
import io.github.returnaction.fitness.coins.stake.ReleaseStakeService;
import io.github.returnaction.fitness.coins.stake.ReserveStakeResult;
import io.github.returnaction.fitness.coins.stake.StakeCancellationService;
import io.github.returnaction.fitness.coins.stake.StakeOperationQueryService;
import io.github.returnaction.fitness.coins.stake.StakeReservationService;
import io.github.returnaction.fitness.coins.stake.StakeSetService;
import io.github.returnaction.fitness.coins.stake.exception.OperationIdConflictException;
import io.github.returnaction.fitness.coins.stake.exception.WalletNotFoundException;
import io.github.returnaction.fitness.contracts.coins.stake.v1.GetStakeOperationRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.GetStakeOperationResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseOutcome;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseStakeRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseStakeResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveOutcome;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveStakeRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveStakeResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.StakeOperationKind;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StakeGrpcServiceTest {

    private final StakeReservationService reservations = mock(StakeReservationService.class);
    private final ReleaseStakeService releases = mock(ReleaseStakeService.class);
    private final StakeOperationQueryService queries = mock(StakeOperationQueryService.class);
    private final StakeSetService stakeSets = mock(StakeSetService.class);
    private final StakeCancellationService cancellations = mock(StakeCancellationService.class);
    private final StakeGrpcService service = new StakeGrpcService(reservations, releases, queries, stakeSets,
            cancellations);

    @Test
    void mapsReserveOutcomeAndHold() {
        UUID operationId = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        when(reservations.reserve(any())).thenReturn(new ReserveStakeResult(operationId,
                io.github.returnaction.fitness.coins.stake.ReserveOutcome.RESERVED, holdId));
        var observer = new CapturingObserver<ReserveStakeResponse>();

        service.reserveStake(request(operationId), observer);

        assertThat(observer.value.getJoinOperationId()).isEqualTo(operationId.toString());
        assertThat(observer.value.getOutcome()).isEqualTo(ReserveOutcome.RESERVE_OUTCOME_RESERVED);
        assertThat(observer.value.getHoldId()).isEqualTo(holdId.toString());
        assertThat(observer.completed).isTrue();
        assertThat(observer.error).isNull();
    }

    @Test
    void mapsDomainErrorsToRichGrpcStatus() throws Exception {
        UUID operationId = UUID.randomUUID();
        doThrow(new WalletNotFoundException()).when(reservations).reserve(any());
        var missingWallet = new CapturingObserver<ReserveStakeResponse>();

        service.reserveStake(request(operationId), missingWallet);

        assertThat(Status.fromThrowable(missingWallet.error).getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(reason(missingWallet.error)).isEqualTo("WALLET_NOT_FOUND");

        doThrow(new OperationIdConflictException()).when(reservations).reserve(any());
        var conflict = new CapturingObserver<ReserveStakeResponse>();

        service.reserveStake(request(operationId), conflict);

        assertThat(Status.fromThrowable(conflict.error).getCode()).isEqualTo(Status.Code.ALREADY_EXISTS);
        assertThat(reason(conflict.error)).isEqualTo("OPERATION_ID_CONFLICT");
    }

    @Test
    void returnsStoredReserveOperation() {
        UUID operationId = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        when(queries.reserve(operationId)).thenReturn(new ReserveStakeResult(operationId,
                io.github.returnaction.fitness.coins.stake.ReserveOutcome.RESERVED, holdId));
        var observer = new CapturingObserver<GetStakeOperationResponse>();

        service.getStakeOperation(GetStakeOperationRequest.newBuilder()
                .setKind(StakeOperationKind.STAKE_OPERATION_KIND_RESERVE)
                .setOperationId(operationId.toString())
                .build(), observer);

        assertThat(observer.value.hasReserve()).isTrue();
        assertThat(observer.value.getReserve().getOutcome()).isEqualTo(ReserveOutcome.RESERVE_OUTCOME_RESERVED);
        assertThat(observer.value.getReserve().getHoldId()).isEqualTo(holdId.toString());
    }

    @Test
    void mapsReleaseReasonAndOutcome() {
        UUID operationId = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        when(releases.release(any())).thenReturn(new ReleaseStakeResult(operationId, holdId,
                io.github.returnaction.fitness.coins.stake.ReleaseOutcome.RELEASED));
        var observer = new CapturingObserver<ReleaseStakeResponse>();

        service.releaseStake(ReleaseStakeRequest.newBuilder()
                .setReleaseOperationId(operationId.toString())
                .setHoldId(holdId.toString())
                .setChallengeId(UUID.randomUUID().toString())
                .setReason(io.github.returnaction.fitness.contracts.coins.stake.v1.ReleaseReason
                        .RELEASE_REASON_CHALLENGE_CANCELLED)
                .build(), observer);

        assertThat(observer.value.getReleaseOperationId()).isEqualTo(operationId.toString());
        assertThat(observer.value.getHoldId()).isEqualTo(holdId.toString());
        assertThat(observer.value.getOutcome()).isEqualTo(ReleaseOutcome.RELEASE_OUTCOME_RELEASED);
    }

    private ReserveStakeRequest request(UUID operationId) {
        return ReserveStakeRequest.newBuilder()
                .setJoinOperationId(operationId.toString())
                .setUserId(UUID.randomUUID().toString())
                .setChallengeId(UUID.randomUUID().toString())
                .setAmount(10)
                .build();
    }

    private static String reason(Throwable error) throws com.google.protobuf.InvalidProtocolBufferException {
        return StatusProto.fromThrowable(error).getDetailsList().getFirst().unpack(ErrorInfo.class).getReason();
    }

    private static final class CapturingObserver<T> implements StreamObserver<T> {

        private T value;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(T next) {
            value = next;
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
