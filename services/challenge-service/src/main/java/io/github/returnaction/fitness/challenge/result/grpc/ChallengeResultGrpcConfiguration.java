package io.github.returnaction.fitness.challenge.result.grpc;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChallengeResultGrpcProperties.class)
class ChallengeResultGrpcConfiguration {
}
