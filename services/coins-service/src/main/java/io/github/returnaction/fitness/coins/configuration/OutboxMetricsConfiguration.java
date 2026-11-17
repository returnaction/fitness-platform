package io.github.returnaction.fitness.coins.configuration;

import io.github.returnaction.fitness.coins.outbox.CoinOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OutboxMetricsConfiguration {

    @Bean
    Gauge coinsOutboxBacklog(CoinOutboxRepository repository, MeterRegistry registry) {
        return Gauge.builder("coins_outbox_backlog", repository,
                        CoinOutboxRepository::countUnpublished)
                .description("Unpublished coins outbox events")
                .register(registry);
    }
}
