package io.github.returnaction.fitness.coins.wallet.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.returnaction.fitness.coins.wallet.service.WalletCreationService;
import io.github.returnaction.fitness.contracts.coins.wallet.creation.v1.CreateWalletIfAbsentRequest;
import io.github.returnaction.fitness.contracts.coins.wallet.creation.v1.CreateWalletIfAbsentResponse;
import io.github.returnaction.fitness.contracts.coins.wallet.creation.v1.CreationStatus;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class WalletCreationGrpcServiceTest {

    private final WalletCreationService creation = mock(WalletCreationService.class);
    private final WalletCreationGrpcService service = new WalletCreationGrpcService(creation);

    @Test
    void returnsReadyAfterCreation() {
        UUID user = UUID.randomUUID();
        CreateWalletIfAbsentRequest request = request(user.toString());
        StreamObserver<CreateWalletIfAbsentResponse> observer = mockObserver();
        service.createWalletIfAbsent(request, observer);
        var order = inOrder(creation, observer);
        order.verify(creation).createWalletIfAbsent(user);
        ArgumentCaptor<CreateWalletIfAbsentResponse> response = ArgumentCaptor
                .forClass(CreateWalletIfAbsentResponse.class);
        order.verify(observer).onNext(response.capture());
        order.verify(observer).onCompleted();
        assertThat(response.getValue().getStatus()).isEqualTo(CreationStatus.CREATION_STATUS_READY);
        assertThat(response.getValue().getUserId()).isEqualTo(user.toString());
    }

    @ParameterizedTest
    @CsvSource({"user,invalid", "user,1-1-1-1-1", "request,invalid", "operation,invalid",
            "user,AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA", "request,''", "operation,''"})
    void rejectsInvalidUuidWithoutCreatingWallet(String field, String value) {
        StreamObserver<CreateWalletIfAbsentResponse> observer = mockObserver();
        var invalidRequest = request(UUID.randomUUID().toString()).toBuilder();
        switch (field) {
            case "user" -> invalidRequest.setUserId(value);
            case "request" -> invalidRequest.setRequestId(value);
            case "operation" -> invalidRequest.setRegistrationOperationId(value);
            default -> throw new IllegalArgumentException("Unknown test field");
        }
        service.createWalletIfAbsent(invalidRequest.build(), observer);
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(creation);
    }

    @Test
    void neverReturnsReadyWhenPersistenceFails() {
        UUID user = UUID.randomUUID();
        doThrow(new IllegalStateException("private detail")).when(creation).createWalletIfAbsent(user);
        StreamObserver<CreateWalletIfAbsentResponse> observer = mockObserver();
        service.createWalletIfAbsent(request(user.toString()), observer);
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(error.getValue().getMessage()).doesNotContain("private detail", user.toString());
        verify(observer, never()).onNext(any());
        verify(observer, never()).onCompleted();
    }

    private CreateWalletIfAbsentRequest request(String user) {
        return CreateWalletIfAbsentRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setRegistrationOperationId(UUID.randomUUID().toString())
                .setUserId(user)
                .build();
    }

    @SuppressWarnings("unchecked")
    private StreamObserver<CreateWalletIfAbsentResponse> mockObserver() {
        return mock(StreamObserver.class);
    }
}
