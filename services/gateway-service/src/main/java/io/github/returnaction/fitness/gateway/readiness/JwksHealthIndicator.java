package io.github.returnaction.fitness.gateway.readiness;

import io.github.returnaction.fitness.gateway.security.jwks.JwksInitializer;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component("jwks")
@RequiredArgsConstructor
public final class JwksHealthIndicator implements HealthIndicator {

    private final JwksInitializer keys;

    @Override
    public Health health() {
        return keys.ready() ? Health.up().build() : Health.down().build();
    }
}
