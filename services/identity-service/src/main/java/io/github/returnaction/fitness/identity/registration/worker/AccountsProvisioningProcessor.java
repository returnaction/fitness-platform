package io.github.returnaction.fitness.identity.registration.worker;

import io.github.returnaction.fitness.identity.registration.grpc.AccountsProvisioningClient;
import io.github.returnaction.fitness.identity.registration.repository.RegistrationLeaseRepository;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Обрабатывает одну уже арендованную операцию без транзакции во время gRPC. */
@Component
@RequiredArgsConstructor
public class AccountsProvisioningProcessor {
    private final AccountsProvisioningClient client;
    private final RegistrationLeaseRepository leases;

    public void process(RegistrationLeaseRepository.Lease lease) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Network provisioning must not run inside a transaction");
        }
        try {
            client.ensureProfile(UUID.randomUUID(), lease.operationId(), lease.userId());
            leases.markProfileReady(lease);
        } catch (StatusRuntimeException exception) {
            Status.Code code = exception.getStatus().getCode();
            boolean retry = switch (code) {
                case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED, INTERNAL -> true;
                default -> false;
            };
            leases.recordFailure(lease, retry, "ACCOUNTS_" + code.name());
        } catch (IllegalStateException exception) {
            leases.recordFailure(lease, false, "ACCOUNTS_INVALID_RESPONSE");
        }
    }
}
