package io.github.returnaction.fitness.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.returnaction.fitness.gateway.configuration.properties.AccessTokenProperties;
import io.github.returnaction.fitness.gateway.configuration.properties.RequestTimeoutProperties;
import io.github.returnaction.fitness.gateway.configuration.properties.SessionVerificationProperties;
import io.github.returnaction.fitness.gateway.configuration.properties.UpstreamProperties;
import io.github.returnaction.fitness.gateway.routing.UpstreamId;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class GatewayPropertiesTest {

    private static final List<String> COMMON_PROPERTIES = List.of(
            "gateway.request.timeout=3s",
            "gateway.security.access-token.jwks-uri=http://localhost/keys",
            "gateway.security.access-token.issuer=identity",
            "gateway.security.access-token.audience=gateway",
            "gateway.security.session-verification.host=localhost",
            "gateway.security.session-verification.port=9091",
            "gateway.security.session-verification.trust-certificate=test/ca.crt",
            "gateway.security.session-verification.certificate=test/client.crt",
            "gateway.security.session-verification.private-key=test/client.key",
            "gateway.security.session-verification.shutdown-timeout=1s");

    private static final List<String> ALL_UPSTREAMS = List.of(
            "gateway.upstreams.services.IDENTITY.base-url=http://localhost:8081",
            "gateway.upstreams.services.ACCOUNT.base-url=http://localhost:8082",
            "gateway.upstreams.services.COINS.base-url=http://localhost:8083",
            "gateway.upstreams.services.CHALLENGE.base-url=http://localhost:8084");

    private final ApplicationContextRunner runner = runnerWith(ALL_UPSTREAMS);

    @Test
    void acceptsValidConfiguration() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsConfigurationWithoutEveryUpstream() {
        List<String> withoutChallenge = ALL_UPSTREAMS.stream()
                .filter(property -> !property.contains("CHALLENGE"))
                .toList();
        runnerWith(withoutChallenge).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("Every known upstream must be configured");
        });
    }

    @Test
    void flagsIncompleteUpstreamMap() {
        var settings = new UpstreamProperties.Settings(URI.create("http://localhost:8081"));
        var complete = new UpstreamProperties(Map.of(
                UpstreamId.IDENTITY, settings,
                UpstreamId.ACCOUNT, settings,
                UpstreamId.COINS, settings,
                UpstreamId.CHALLENGE, settings));
        assertThat(complete.isEveryUpstreamConfigured()).isTrue();

        var withoutChallenge = new UpstreamProperties(Map.of(
                UpstreamId.IDENTITY, settings,
                UpstreamId.ACCOUNT, settings,
                UpstreamId.COINS, settings));
        assertThat(withoutChallenge.isEveryUpstreamConfigured()).isFalse();
    }

    @Test
    void rejectsInvalidSettings() {
        for (String property : new String[]{"gateway.request.timeout=0s", "gateway.request.timeout=999999999999d",
                "gateway.security.access-token.issuer=", "gateway.security.access-token.jwks-uri=file:/keys",
                "gateway.security.session-verification.port=65536",
                "gateway.security.session-verification.shutdown-timeout=-1s",
                "gateway.upstreams.services.IDENTITY.base-url=http://localhost/path",
                "gateway.upstreams.services.IDENTITY.base-url=http://user:password@localhost"}) {
            runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void rejectsMissingConfiguration() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
                .run(context -> assertThat(context).hasFailed());
    }

    private static ApplicationContextRunner runnerWith(List<String> upstreamProperties) {
        var properties = new ArrayList<>(COMMON_PROPERTIES);
        properties.addAll(upstreamProperties);
        return new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(properties.toArray(String[]::new));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AccessTokenProperties.class, RequestTimeoutProperties.class,
            SessionVerificationProperties.class, UpstreamProperties.class})
    static class PropertiesConfiguration {
    }
}
