package io.github.returnaction.fitness.challenge.result.grpc;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "challenge.result.grpc")
public record ChallengeResultGrpcProperties(boolean enabled, String address, int port, String certificate,
                                            String privateKey, String trustCertificate,
                                            String resultSourceSan, String coinsSan) {
}
