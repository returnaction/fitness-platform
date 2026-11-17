package io.github.returnaction.fitness.challenge.coins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.Any;
import com.google.rpc.ErrorInfo;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsOperationConflictException;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsOutcomeUnknownException;
import io.github.returnaction.fitness.challenge.coins.exception.CoinsRejectedException;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveOutcome;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveStakeRequest;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveStakeResponse;
import io.github.returnaction.fitness.contracts.coins.stake.v1.StakeServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CoinsStakeClientTest {

    private final StakeServiceGrpc.StakeServiceBlockingStub stub =
            mock(StakeServiceGrpc.StakeServiceBlockingStub.class);
    private final CoinsStakeClient client = new CoinsStakeClient(stub, Duration.ofSeconds(3));

    @Test
    void usesDeadlineBelowGatewayAndReturnsResponse() {
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        var expected = ReserveStakeResponse.newBuilder()
                .setJoinOperationId(UUID.randomUUID().toString())
                .setOutcome(ReserveOutcome.RESERVE_OUTCOME_RESERVED)
                .setHoldId(UUID.randomUUID().toString())
                .build();
        when(stub.reserveStake(any(ReserveStakeRequest.class))).thenReturn(expected);

        var response = client.reserveStake(ReserveStakeRequest.getDefaultInstance());

        assertThat(response).isEqualTo(expected);
        ArgumentCaptor<Long> deadline = ArgumentCaptor.forClass(Long.class);
        verify(stub).withDeadlineAfter(deadline.capture(), any(TimeUnit.class));
        assertThat(deadline.getValue()).isPositive().isLessThan(7_000L);
    }

    @Test
    void retriesOnceOnUnavailableAndReportsUnknownOutcome() {
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.reserveStake(any(ReserveStakeRequest.class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());

        assertThatThrownBy(() -> client.reserveStake(ReserveStakeRequest.getDefaultInstance()))
                .isInstanceOf(CoinsOutcomeUnknownException.class);
        verify(stub, times(2)).reserveStake(any(ReserveStakeRequest.class));
    }

    @Test
    void doesNotRetryPermanentRejection() {
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.reserveStake(any(ReserveStakeRequest.class)))
                .thenThrow(Status.INVALID_ARGUMENT.withDescription("invalid").asRuntimeException());

        assertThatThrownBy(() -> client.reserveStake(ReserveStakeRequest.getDefaultInstance()))
                .isInstanceOf(CoinsRejectedException.class);
        verify(stub, times(1)).reserveStake(any(ReserveStakeRequest.class));
    }

    @Test
    void mapsOperationIdConflictAsPermanentError() {
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        var status = com.google.rpc.Status.newBuilder()
                .setCode(Status.Code.ALREADY_EXISTS.value())
                .setMessage("conflict")
                .addDetails(Any.pack(ErrorInfo.newBuilder()
                        .setReason("OPERATION_ID_CONFLICT")
                        .setDomain("coins.fitness.internal")
                        .build()))
                .build();
        when(stub.reserveStake(any(ReserveStakeRequest.class)))
                .thenThrow(StatusProto.toStatusRuntimeException(status));

        assertThatThrownBy(() -> client.reserveStake(ReserveStakeRequest.getDefaultInstance()))
                .isInstanceOf(CoinsOperationConflictException.class);
        verify(stub, times(1)).reserveStake(any(ReserveStakeRequest.class));
    }

    @Test
    void rejectsDeadlineThatIsNotBelowGatewayBudget() {
        assertThatThrownBy(() -> new CoinsStakeProperties("localhost", 9092, "c", "k", "ca",
                Duration.ofSeconds(7)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
