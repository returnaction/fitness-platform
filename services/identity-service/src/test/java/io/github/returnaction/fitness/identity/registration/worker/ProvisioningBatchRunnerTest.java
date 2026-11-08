package io.github.returnaction.fitness.identity.registration.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.returnaction.fitness.identity.registration.repository.RegistrationLeaseRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ProvisioningBatchRunnerTest {
    @Test
    void workerFailuresDoNotLogPrivateExceptionDetails() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProvisioningBatchRunner.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var lease = new RegistrationLeaseRepository.Lease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);
        try (var runner = new ProvisioningBatchRunner(1, 1)) {
            runner.runPass(
                    () -> {
                        throw new IllegalStateException("private@example.com");
                    },
                    ignored -> {
                    });
            runner.runPass(() -> List.of(lease), ignored -> {
                throw new IllegalStateException("secret-password");
            });
            assertThat(appender.list).hasSize(2);
            for (var event : appender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain("private@example.com", "secret-password");
                assertThat(event.getThrowableProxy()).isNull();
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void limitsPassAndDoesNotClaimMoreThanAvailableSlots() throws Exception {
        var claims = new AtomicInteger();
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var started = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var lease = new RegistrationLeaseRepository.Lease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);
        try (var runner = new ProvisioningBatchRunner(20, 4);
                var coordinator = Executors.newSingleThreadExecutor()) {
            var pass = coordinator.submit(() -> runner.runPass(
                    () -> {
                        claims.incrementAndGet();
                        return List.of(lease);
                    },
                    ignored -> {
                        int current = active.incrementAndGet();
                        peak.accumulateAndGet(current, Math::max);
                        started.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("Timed out");
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        } finally {
                            active.decrementAndGet();
                        }
                    }));
            try {
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(claims.get()).isEqualTo(4);
                var otherClaims = new AtomicInteger();
                runner.runPass(
                        () -> {
                            otherClaims.incrementAndGet();
                            return List.of();
                        },
                        ignored -> {
                        });
                assertThat(otherClaims.get()).isZero();
            } finally {
                release.countDown();
            }
            pass.get(15, TimeUnit.SECONDS);
            assertThat(claims.get()).isEqualTo(20);
            assertThat(peak.get()).isEqualTo(4);
        }
    }

    @Test
    void rejectsSettingsOutsideAgreedLimits() {
        assertThatThrownBy(() -> new ProvisioningBatchRunner(21, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProvisioningBatchRunner(20, 5)).isInstanceOf(IllegalArgumentException.class);
    }
}
