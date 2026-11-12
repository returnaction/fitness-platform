package io.github.returnaction.fitness.identity.registration.service;

import io.github.returnaction.fitness.identity.registration.repository.RegistrationReadinessRepository;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ожидает завершения фоновой подготовки без удержания транзакции между
 * проверками.
 */
@Component
public final class RegistrationCompletionWaiter {

    private final RegistrationReadinessRepository repository;
    private final long waitMillis;
    private final long pollMillis;

    public RegistrationCompletionWaiter(
                                        RegistrationReadinessRepository repository,
                                        @Value("${identity.registration.response-wait-ms}") long waitMillis,
                                        @Value("${identity.registration.response-poll-ms}") long pollMillis) {
        if (waitMillis < 1 || waitMillis > 30000 || pollMillis < 1 || pollMillis > waitMillis) {
            throw new IllegalArgumentException("Invalid registration response wait configuration");
        }
        this.repository = repository;
        this.waitMillis = waitMillis;
        this.pollMillis = pollMillis;
    }

    public boolean await(UUID operationId) {
        long deadline = System.nanoTime() + Duration.ofMillis(waitMillis).toNanos();
        while (true) {
            String state = repository.findStatus(operationId);
            if ("COMPLETED".equals(state))
                return true;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0)
                return false;
            try {
                Thread.sleep(Duration.ofNanos(
                        Math.min(remaining, Duration.ofMillis(pollMillis).toNanos())));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
