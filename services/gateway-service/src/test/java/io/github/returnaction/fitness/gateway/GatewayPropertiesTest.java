package io.github.returnaction.fitness.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.returnaction.fitness.gateway.configuration.properties.AccessTokenProperties;
import io.github.returnaction.fitness.gateway.configuration.properties.RequestTimeoutProperties;
import io.github.returnaction.fitness.gateway.configuration.properties.SessionVerificationProperties;
import io.github.returnaction.fitness.gateway.configuration.properties.UpstreamProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class GatewayPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues("gateway.request.timeout=3s",
                    "gateway.upstreams.services.IDENTITY.base-url=http://localhost:8081",
                    "gateway.security.access-token.jwks-uri=http://localhost/keys",
                    "gateway.security.access-token.issuer=identity",
                    "gateway.security.access-token.audience=gateway",
                    "gateway.security.session-verification.host=localhost",
                    "gateway.security.session-verification.port=9091",
                    "gateway.security.session-verification.trust-certificate=test/ca.crt",
                    "gateway.security.session-verification.certificate=test/client.crt",
                    "gateway.security.session-verification.private-key=test/client.key",
                    "gateway.security.session-verification.shutdown-timeout=1s");

    @Test
    void acceptsValidConfiguration() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsInvalidSettings() {
        for (String property : new String[]{
                "gateway.request.timeout=0s",
                "gateway.request.timeout=999999999999d",
                "gateway.security.access-token.issuer=",
                "gateway.security.access-token.jwks-uri=file:/keys",
                "gateway.security.session-verification.port=65536",
                "gateway.security.session-verification.shutdown-timeout=-1s",
                "gateway.upstreams.services.IDENTITY.base-url=http://localhost/path",
                "gateway.upstreams.services.IDENTITY.base-url=http://user:password@localhost"
        }) {
            runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void rejectsMissingConfiguration() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionRejectsUnprotectedEndpoints() {
        runner.withUserConfiguration(io.github.returnaction.fitness.gateway.configuration.ProductionConfiguration.class)
                .withPropertyValues("spring.profiles.active=production")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionAcceptsHttpsEndpoints() {
        runner.withUserConfiguration(io.github.returnaction.fitness.gateway.configuration.ProductionConfiguration.class)
                .withPropertyValues("spring.profiles.active=production",
                        "gateway.security.access-token.jwks-uri=https://localhost/keys",
                        "gateway.upstreams.services.IDENTITY.base-url=https://localhost")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AccessTokenProperties.class, RequestTimeoutProperties.class,
            SessionVerificationProperties.class, UpstreamProperties.class})
    static class PropertiesConfiguration {
    }
}
