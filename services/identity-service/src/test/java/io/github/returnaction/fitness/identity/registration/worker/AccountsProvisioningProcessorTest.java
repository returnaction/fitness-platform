package io.github.returnaction.fitness.identity.registration.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.returnaction.fitness.identity.registration.grpc.AccountsProvisioningClient;
import io.grpc.Status;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountsProvisioningProcessorTest {
    private final AccountsProvisioningClient client = mock(AccountsProvisioningClient.class);
    private final RegistrationLeaseRepository repository = mock(RegistrationLeaseRepository.class);
    private final AccountsProvisioningProcessor processor = new AccountsProvisioningProcessor(client, repository);
    private final RegistrationLeaseRepository.Lease lease =
            new RegistrationLeaseRepository.Lease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);

    @Test
    void recordsReadyAfterSuccessfulCall() {
        processor.process(lease);
        verify(repository).markProfileReady(lease);
    }

    @Test
    void retriesUnavailableAndBlocksPermissionFailure() {
        doThrow(Status.UNAVAILABLE.asRuntimeException(), Status.PERMISSION_DENIED.asRuntimeException())
                .when(client)
                .ensureProfile(any(), eq(lease.operationId()), eq(lease.userId()));
        processor.process(lease);
        processor.process(lease);
        verify(repository).recordFailure(lease, true, "ACCOUNTS_UNAVAILABLE");
        verify(repository).recordFailure(lease, false, "ACCOUNTS_PERMISSION_DENIED");
    }
}
