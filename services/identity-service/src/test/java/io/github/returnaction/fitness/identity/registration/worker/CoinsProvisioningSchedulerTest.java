package io.github.returnaction.fitness.identity.registration.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.returnaction.fitness.identity.registration.repository.RegistrationLeaseRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoinsProvisioningSchedulerTest {
    @Test
    void processesOnlyConfiguredBatchAndClaimsOneAtATime() {
        var repository = mock(RegistrationLeaseRepository.class);
        var processor = mock(CoinsProvisioningProcessor.class);
        var lease = new RegistrationLeaseRepository.Lease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);
        when(repository.claimWallet(any(), eq(1), eq(30))).thenReturn(List.of(lease));
        try (var runner = new ProvisioningBatchRunner(4, 4)) {
            new CoinsProvisioningScheduler(repository, processor, runner, 30).poll();
        }
        verify(repository, times(4)).claimWallet(any(), eq(1), eq(30));
        verify(processor, times(4)).process(lease);
    }
}
