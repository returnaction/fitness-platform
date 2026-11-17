package io.github.returnaction.fitness.coins.challenge;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChallengeResultProperties.class)
class ChallengeResultConfiguration {

    @Bean
    ChallengeResultClient challengeResultClient(ChallengeResultProperties properties) {
        return new ChallengeResultGrpcClient(properties);
    }
}
