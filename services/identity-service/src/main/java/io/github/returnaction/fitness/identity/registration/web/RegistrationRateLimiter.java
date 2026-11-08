package io.github.returnaction.fitness.identity.registration.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.returnaction.fitness.identity.registration.exception.RegistrationRateLimitException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Локальный token bucket для IP прямого соединения одного экземпляра Identity.
 */
@Component
public final class RegistrationRateLimiter {

    private final long capacity;
    private final Duration period;
    private final Cache<String, Bucket> buckets;

    public RegistrationRateLimiter(@Value("${identity.registration.rate-limit.capacity}") long capacity,
                                   @Value("${identity.registration.rate-limit.period-seconds}") long periodSeconds,
                                   @Value("${identity.registration.rate-limit.cache-size}") long cacheSize,
                                   @Value("${identity.registration.rate-limit.cache-expiry-seconds}") long cacheExpirySeconds) {
        if (capacity < 1 || periodSeconds < 1 || cacheSize < 1 || cacheExpirySeconds < periodSeconds) {
            throw new IllegalArgumentException("Invalid registration rate limit configuration");
        }
        this.capacity = capacity;
        this.period = Duration.ofSeconds(periodSeconds);
        this.buckets = Caffeine.newBuilder()
                .maximumSize(cacheSize)
                .expireAfterAccess(Duration.ofSeconds(cacheExpirySeconds))
                .build();
    }

    public void check(String remoteAddress) {
        String key = remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
        Bucket bucket = buckets.get(
                key,
                ignored -> Bucket.builder()
                        .addLimit(limit -> limit.capacity(capacity).refillIntervally(capacity, period))
                        .build());
        var result = bucket.tryConsumeAndReturnRemaining(1);
        if (!result.isConsumed()) {
            long nanos = result.getNanosToWaitForRefill();
            long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(nanos - 1) + 1);
            throw new RegistrationRateLimitException(seconds);
        }
    }
}
