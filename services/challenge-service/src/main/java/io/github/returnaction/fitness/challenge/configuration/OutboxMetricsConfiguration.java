package io.github.returnaction.fitness.challenge.configuration;

import io.github.returnaction.fitness.challenge.repository.ChallengeOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OutboxMetricsConfiguration {

    @Bean
    Gauge challengeOutboxBacklog(ChallengeOutboxRepository repository, MeterRegistry registry) {
        return Gauge.builder("challenge_outbox_backlog", repository,
                        ChallengeOutboxRepository::countUnpublished)
                .description("Unpublished challenge outbox events")
                .register(registry);
    }
}
