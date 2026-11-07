package io.github.returnaction.fitness.identity.registration.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountsProvisioningSchedulerTest {
    @Test
    void processesOnlyConfiguredBatchAndClaimsOneAtATime() {
        var repository = mock(RegistrationLeaseRepository.class);
        var processor = mock(AccountsProvisioningProcessor.class);
        var lease = new RegistrationLeaseRepository.Lease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);
        when(repository.claim(any(), eq(1), eq(30))).thenReturn(List.of(lease));
        try (var runner = new ProvisioningBatchRunner(4, 4)) {
            new AccountsProvisioningScheduler(repository, processor, runner, 30).poll();
        }
        verify(repository, times(4)).claim(any(), eq(1), eq(30));
        verify(processor, times(4)).process(lease);
    }
}
