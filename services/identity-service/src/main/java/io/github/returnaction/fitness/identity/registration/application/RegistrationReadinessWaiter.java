package io.github.returnaction.fitness.identity.registration.application;

import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Ожидает завершения фоновой подготовки без удержания транзакции между проверками. */
@Component
public final class RegistrationReadinessWaiter {
    private final JdbcClient jdbc;
    private final long waitMillis;
    private final long pollMillis;

    public RegistrationReadinessWaiter(
            JdbcClient jdbc,
            @Value("${identity.registration.response-wait-ms}") long waitMillis,
            @Value("${identity.registration.response-poll-ms}") long pollMillis) {
        if (waitMillis < 1 || waitMillis > 30000 || pollMillis < 1 || pollMillis > waitMillis) {
            throw new IllegalArgumentException("Invalid registration response wait configuration");
        }
        this.jdbc = jdbc;
        this.waitMillis = waitMillis;
        this.pollMillis = pollMillis;
    }

    public boolean await(UUID operationId) {
        long deadline = System.nanoTime() + Duration.ofMillis(waitMillis).toNanos();
        while (true) {
            String state = jdbc.sql("SELECT status FROM registration_operations WHERE id = :id")
                    .param("id", operationId)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new IllegalStateException("Registration operation is missing"));
            if ("COMPLETED".equals(state)) return true;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
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
