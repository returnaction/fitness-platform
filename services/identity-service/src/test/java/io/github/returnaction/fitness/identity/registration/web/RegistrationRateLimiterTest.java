package io.github.returnaction.fitness.identity.registration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RegistrationRateLimiterTest {
    @Test
    void allowsFiveAndRejectsSixthSeparatelyForEachAddress() {
        var limiter = new RegistrationRateLimiter(5, 60, 100, 120);
        for (int i = 0; i < 5; i++) limiter.check("192.0.2.1");
        assertThatThrownBy(() -> limiter.check("192.0.2.1"))
                .isInstanceOfSatisfying(
                        RegistrationRateLimitException.class,
                        error -> assertThat(error.retryAfterSeconds()).isBetween(1L, 60L));
        limiter.check("192.0.2.2");
    }

    @Test
    void concurrentRequestsCannotExceedLimit() throws Exception {
        var limiter = new RegistrationRateLimiter(5, 60, 100, 120);
        var start = new CountDownLatch(1);
        var accepted = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(20)) {
            for (int i = 0; i < 20; i++) {
                executor.submit(() -> {
                    start.await();
                    try {
                        limiter.check("192.0.2.1");
                        accepted.incrementAndGet();
                    } catch (RegistrationRateLimitException expected) {
                        // Проверяем количество успешных запросов.
                    }
                    return null;
                });
            }
            start.countDown();
        }
        assertThat(accepted.get()).isEqualTo(5);
    }

    @Test
    void refillsAfterConfiguredPeriod() throws Exception {
        var limiter = new RegistrationRateLimiter(1, 1, 100, 2);
        limiter.check("192.0.2.1");
        assertThatThrownBy(() -> limiter.check("192.0.2.1")).isInstanceOf(RegistrationRateLimitException.class);
        Thread.sleep(1100);
        limiter.check("192.0.2.1");
    }
}
