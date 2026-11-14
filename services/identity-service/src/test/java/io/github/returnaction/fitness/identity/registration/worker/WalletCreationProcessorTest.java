package io.github.returnaction.fitness.identity.registration.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.returnaction.fitness.identity.registration.grpc.WalletCreationClient;
import io.github.returnaction.fitness.identity.registration.repository.RegistrationLeaseRepository;
import io.grpc.Status;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WalletCreationProcessorTest {

    private final WalletCreationClient client = mock(WalletCreationClient.class);
    private final RegistrationLeaseRepository repository = mock(RegistrationLeaseRepository.class);
    private final WalletCreationProcessor processor = new WalletCreationProcessor(client, repository);
    private final RegistrationLeaseRepository.Lease lease = new RegistrationLeaseRepository.Lease(UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), 1);

    @Test
    void recordsReadyAfterSuccessfulCall() {
        processor.process(lease);
        verify(repository).completeRegistrationAndActivateUser(lease);
    }

    @Test
    void retriesUnavailableAndBlocksPermissionFailure() {
        doThrow(Status.UNAVAILABLE.asRuntimeException(), Status.PERMISSION_DENIED.asRuntimeException()).when(client)
                .createWalletIfAbsent(any(), eq(lease.operationId()), eq(lease.userId()));
        processor.process(lease);
        processor.process(lease);
        verify(repository).recordFailure(lease, true, "COINS_UNAVAILABLE");
        verify(repository).recordFailure(lease, false, "COINS_PERMISSION_DENIED");
    }

    @Test
    void retriesTimedOutCallAndMarksReadyAfterSuccessfulRepeat() {
        doThrow(Status.DEADLINE_EXCEEDED.asRuntimeException()).doNothing()
                .when(client)
                .createWalletIfAbsent(any(), eq(lease.operationId()), eq(lease.userId()));
        processor.process(lease);
        processor.process(lease);
        var order = inOrder(repository);
        order.verify(repository).recordFailure(lease, true, "COINS_DEADLINE_EXCEEDED");
        order.verify(repository).completeRegistrationAndActivateUser(lease);
    }

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {"UNAVAILABLE", "DEADLINE_EXCEEDED", "RESOURCE_EXHAUSTED", "ABORTED",
            "INTERNAL"})
    void retriesTransientGrpcErrors(Status.Code code) {
        doThrow(code.toStatus().asRuntimeException()).when(client)
                .createWalletIfAbsent(any(), eq(lease.operationId()), eq(lease.userId()));
        processor.process(lease);
        verify(repository).recordFailure(lease, true, "COINS_" + code.name());
        verify(repository, never()).completeRegistrationAndActivateUser(lease);
    }

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {"INVALID_ARGUMENT", "PERMISSION_DENIED", "UNAUTHENTICATED",
            "FAILED_PRECONDITION"})
    void blocksPermanentGrpcErrors(Status.Code code) {
        doThrow(code.toStatus().asRuntimeException()).when(client)
                .createWalletIfAbsent(any(), eq(lease.operationId()), eq(lease.userId()));
        processor.process(lease);
        verify(repository).recordFailure(lease, false, "COINS_" + code.name());
        verify(repository, never()).completeRegistrationAndActivateUser(lease);
    }

    @Test
    void blocksInvalidServiceResponse() {
        doThrow(new IllegalStateException("invalid response")).when(client)
                .createWalletIfAbsent(any(), eq(lease.operationId()), eq(lease.userId()));
        processor.process(lease);
        verify(repository).recordFailure(lease, false, "COINS_INVALID_RESPONSE");
        verify(repository, never()).completeRegistrationAndActivateUser(lease);
    }
}
