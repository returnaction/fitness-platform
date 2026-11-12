package io.github.returnaction.fitness.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import io.github.returnaction.fitness.gateway.configuration.properties.AccessTokenProperties;
import io.github.returnaction.fitness.gateway.configuration.properties.RequestTimeoutProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayApplicationTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private io.github.returnaction.fitness.gateway.security.session.SessionVerificationClient sessions;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private io.github.returnaction.fitness.gateway.security.jwks.JwksInitializer jwks;

    @Autowired
    private AccessTokenProperties tokenProperties;
    @Autowired
    private RequestTimeoutProperties timeoutProperties;

    @Test
    void startsWithIsolatedTestConfiguration() {
        assertThat(tokenProperties.issuer()).isEqualTo("test-identity");
        assertThat(timeoutProperties.timeout()).isEqualTo(Duration.ofSeconds(3));
    }
}
