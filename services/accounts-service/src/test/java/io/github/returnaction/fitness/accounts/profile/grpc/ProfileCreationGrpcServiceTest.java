package io.github.returnaction.fitness.accounts.profile.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.returnaction.fitness.accounts.profile.exception.ProfileCreationExhaustedException;
import io.github.returnaction.fitness.accounts.profile.service.ProfileCreationService;
import io.github.returnaction.fitness.contracts.accounts.profile.creation.v1.CreateProfileIfAbsentRequest;
import io.github.returnaction.fitness.contracts.accounts.profile.creation.v1.CreateProfileIfAbsentResponse;
import io.github.returnaction.fitness.contracts.accounts.profile.creation.v1.CreationStatus;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProfileCreationGrpcServiceTest {
    private final ProfileCreationService profileCreationService = mock(ProfileCreationService.class);
    private final ProfileCreationGrpcService service = new ProfileCreationGrpcService(profileCreationService);

    @Test
    void returnsReadyAfterCreation() {
        UUID user = UUID.randomUUID();
        CreateProfileIfAbsentRequest request = request(user.toString());
        StreamObserver<CreateProfileIfAbsentResponse> observer = mockObserver();
        service.createProfileIfAbsent(request, observer);
        var order = inOrder(profileCreationService, observer);
        order.verify(profileCreationService).createIfAbsent(user);
        ArgumentCaptor<CreateProfileIfAbsentResponse> response = ArgumentCaptor
                .forClass(CreateProfileIfAbsentResponse.class);
        order.verify(observer).onNext(response.capture());
        order.verify(observer).onCompleted();
        assertThat(response.getValue().getStatus()).isEqualTo(CreationStatus.CREATION_STATUS_READY);
        assertThat(response.getValue().getUserId()).isEqualTo(user.toString());
    }

    @Test
    void rejectsInvalidUuidWithoutCreatingProfile() {
        StreamObserver<CreateProfileIfAbsentResponse> observer = mockObserver();
        service.createProfileIfAbsent(request("invalid"), observer);
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(profileCreationService);
    }

    @Test
    void neverReturnsReadyWhenPersistenceFails() {
        UUID user = UUID.randomUUID();
        doThrow(new IllegalStateException("private detail")).when(profileCreationService).createIfAbsent(user);
        StreamObserver<CreateProfileIfAbsentResponse> observer = mockObserver();
        service.createProfileIfAbsent(request(user.toString()), observer);
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(error.getValue().getMessage()).doesNotContain("private detail", user.toString());
        verify(observer, never()).onNext(any());
        verify(observer, never()).onCompleted();
    }

    @Test
    void returnsAbortedWhenProfileCreationAttemptsAreExhausted() {
        UUID user = UUID.randomUUID();
        doThrow(new ProfileCreationExhaustedException()).when(profileCreationService).createIfAbsent(user);
        StreamObserver<CreateProfileIfAbsentResponse> observer = mockObserver();

        service.createProfileIfAbsent(request(user.toString()), observer);

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(Status.Code.ABORTED);
        verify(observer, never()).onNext(any());
        verify(observer, never()).onCompleted();
    }

    private CreateProfileIfAbsentRequest request(String user) {
        return CreateProfileIfAbsentRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setRegistrationOperationId(UUID.randomUUID().toString())
                .setUserId(user)
                .build();
    }

    @SuppressWarnings("unchecked")
    private StreamObserver<CreateProfileIfAbsentResponse> mockObserver() {
        return mock(StreamObserver.class);
    }
}
