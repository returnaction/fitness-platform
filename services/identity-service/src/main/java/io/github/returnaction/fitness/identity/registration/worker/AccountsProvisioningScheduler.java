package io.github.returnaction.fitness.identity.registration.worker;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Однопоточная обработка ограниченной партии: сетевой вызов вне транзакции claim. */
@Component
@EnableScheduling
@ConditionalOnProperty(
        name = {"identity.registration.worker.enabled", "identity.accounts-grpc.enabled"},
        havingValue = "true")
public final class AccountsProvisioningScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountsProvisioningScheduler.class);
    private final UUID owner = UUID.randomUUID();
    private final RegistrationLeaseRepository leases;
    private final AccountsProvisioningProcessor processor;
    private final int batchSize;
    private final int leaseSeconds;

    public AccountsProvisioningScheduler(
            RegistrationLeaseRepository leases,
            AccountsProvisioningProcessor processor,
            @Value("${identity.registration.worker.batch-size}") int batchSize,
            @Value("${identity.registration.worker.lease-seconds}") int leaseSeconds) {
        if (batchSize < 1 || batchSize > 4 || leaseSeconds < 16) {
            throw new IllegalArgumentException("Invalid worker batch or lease");
        }
        this.leases = leases;
        this.processor = processor;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(fixedDelayString = "${identity.registration.worker.poll-ms}")
    public void poll() {
        for (int index = 0; index < batchSize; index++) {
            var claimed = leases.claim(owner, 1, leaseSeconds);
            if (claimed.isEmpty()) {
                return;
            }
            try {
                processor.process(claimed.getFirst());
            } catch (RuntimeException exception) {
                LOGGER.error("Accounts provisioning worker failed; lease will expire", exception);
            }
        }
    }
}
