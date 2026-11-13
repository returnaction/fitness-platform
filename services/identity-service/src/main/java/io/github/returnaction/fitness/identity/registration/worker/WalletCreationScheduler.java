package io.github.returnaction.fitness.identity.registration.worker;

import io.github.returnaction.fitness.identity.registration.repository.RegistrationLeaseRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Запускает обработку кошельков в общем ограниченном пуле. */
@Component
@EnableScheduling
public final class WalletCreationScheduler {

    private final UUID owner = UUID.randomUUID();
    private final RegistrationLeaseRepository leases;
    private final WalletCreationProcessor processor;
    private final RegistrationTaskRunner batchRunner;
    private final int leaseSeconds;

    public WalletCreationScheduler(RegistrationLeaseRepository leases, WalletCreationProcessor processor,
        RegistrationTaskRunner batchRunner, @Value("${identity.registration.worker.lease-seconds}") int leaseSeconds) {
        if (leaseSeconds < 16) {
            throw new IllegalArgumentException("Invalid worker lease");
        }
        this.leases = leases;
        this.processor = processor;
        this.batchRunner = batchRunner;
        this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(fixedDelayString = "${identity.registration.worker.poll-ms}")
    public void poll() {
        batchRunner.runPass(() -> leases.claimWalletCreation(owner, 1, leaseSeconds), processor::process);
    }
}
