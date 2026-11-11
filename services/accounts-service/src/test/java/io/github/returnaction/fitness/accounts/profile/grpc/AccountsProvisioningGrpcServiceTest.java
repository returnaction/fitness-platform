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
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.EnsureProfileRequest;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.EnsureProfileResponse;
import io.github.returnaction.fitness.contracts.accounts.provisioning.v1.ProvisioningStatus;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountsProvisioningGrpcServiceTest {
    private final ProfileCreationService profileCreationService = mock(ProfileCreationService.class);
    private final AccountsProvisioningGrpcService service = new AccountsProvisioningGrpcService(profileCreationService);

    @Test
    void returnsReadyAfterProvisioning() {
        UUID user = UUID.randomUUID();
        EnsureProfileRequest request = request(user.toString());
        StreamObserver<EnsureProfileResponse> observer = mockObserver();
        service.ensureProfile(request, observer);
        var order = inOrder(profileCreationService, observer);
        order.verify(profileCreationService).createIfAbsent(user);
        ArgumentCaptor<EnsureProfileResponse> response = ArgumentCaptor.forClass(EnsureProfileResponse.class);
        order.verify(observer).onNext(response.capture());
        order.verify(observer).onCompleted();
        assertThat(response.getValue().getStatus()).isEqualTo(ProvisioningStatus.PROVISIONING_STATUS_READY);
        assertThat(response.getValue().getUserId()).isEqualTo(user.toString());
    }

    @Test
    void rejectsInvalidUuidWithoutCreatingProfile() {
        StreamObserver<EnsureProfileResponse> observer = mockObserver();
        service.ensureProfile(request("invalid"), observer);
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(profileCreationService);
    }

    @Test
    void neverReturnsReadyWhenPersistenceFails() {
        UUID user = UUID.randomUUID();
        doThrow(new IllegalStateException("private detail")).when(profileCreationService).createIfAbsent(user);
        StreamObserver<EnsureProfileResponse> observer = mockObserver();
        service.ensureProfile(request(user.toString()), observer);
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
        StreamObserver<EnsureProfileResponse> observer = mockObserver();

        service.ensureProfile(request(user.toString()), observer);

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(Status.Code.ABORTED);
        verify(observer, never()).onNext(any());
        verify(observer, never()).onCompleted();
    }

    private EnsureProfileRequest request(String user) {
        return EnsureProfileRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setRegistrationOperationId(UUID.randomUUID().toString())
                .setUserId(user)
                .build();
    }

    @SuppressWarnings("unchecked")
    private StreamObserver<EnsureProfileResponse> mockObserver() {
        return mock(StreamObserver.class);
    }
}
