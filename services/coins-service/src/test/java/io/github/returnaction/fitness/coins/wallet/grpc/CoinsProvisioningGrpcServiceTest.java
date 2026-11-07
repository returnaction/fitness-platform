package io.github.returnaction.fitness.coins.wallet.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.returnaction.fitness.coins.wallet.WalletProvisioningService;
import io.github.returnaction.fitness.contracts.coins.provisioning.v1.EnsureWalletRequest;
import io.github.returnaction.fitness.contracts.coins.provisioning.v1.EnsureWalletResponse;
import io.github.returnaction.fitness.contracts.coins.provisioning.v1.ProvisioningStatus;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CoinsProvisioningGrpcServiceTest {
    private final WalletProvisioningService provisioning = mock(WalletProvisioningService.class);
    private final CoinsProvisioningGrpcService service = new CoinsProvisioningGrpcService(provisioning);

    @Test
    void returnsReadyAfterProvisioning() {
        UUID user = UUID.randomUUID();
        EnsureWalletRequest request = request(user.toString());
        StreamObserver<EnsureWalletResponse> observer = mockObserver();
        service.ensureWallet(request, observer);
        var order = inOrder(provisioning, observer);
        order.verify(provisioning).ensureWallet(user);
        ArgumentCaptor<EnsureWalletResponse> response = ArgumentCaptor.forClass(EnsureWalletResponse.class);
        order.verify(observer).onNext(response.capture());
        order.verify(observer).onCompleted();
        assertThat(response.getValue().getStatus()).isEqualTo(ProvisioningStatus.PROVISIONING_STATUS_READY);
        assertThat(response.getValue().getUserId()).isEqualTo(user.toString());
    }

    @Test
    void rejectsInvalidUuidWithoutCreatingWallet() {
        StreamObserver<EnsureWalletResponse> observer = mockObserver();
        service.ensureWallet(request("invalid"), observer);
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(provisioning);
    }

    @Test
    void neverReturnsReadyWhenPersistenceFails() {
        UUID user = UUID.randomUUID();
        doThrow(new IllegalStateException("private detail")).when(provisioning).ensureWallet(user);
        StreamObserver<EnsureWalletResponse> observer = mockObserver();
        service.ensureWallet(request(user.toString()), observer);
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(error.getValue().getMessage()).doesNotContain("private detail", user.toString());
        verify(observer, never()).onNext(any());
        verify(observer, never()).onCompleted();
    }

    private EnsureWalletRequest request(String user) {
        return EnsureWalletRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setRegistrationOperationId(UUID.randomUUID().toString())
                .setUserId(user)
                .build();
    }

    @SuppressWarnings("unchecked")
    private StreamObserver<EnsureWalletResponse> mockObserver() {
        return mock(StreamObserver.class);
    }
}
