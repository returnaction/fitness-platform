package io.github.returnaction.fitness.challenge.coins;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CoinsStakeProperties.class)
class CoinsStakeConfiguration {

    @Bean
    CoinsStakeClient coinsStakeClient(CoinsStakeProperties properties) {
        return new CoinsStakeClient(properties);
    }
}
