package io.github.returnaction.fitness.identity.session.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.returnaction.fitness.identity.session.dto.LoginRequest;
import io.github.returnaction.fitness.identity.session.dto.LoginTokenResponse;
import io.github.returnaction.fitness.identity.session.dto.RefreshRequest;
import io.github.returnaction.fitness.identity.session.dto.RefreshResponse;
import io.github.returnaction.fitness.identity.shared.model.ValidatedCredentials;
import org.junit.jupiter.api.Test;

class AuthenticationDtoLoggingTest {

    @Test
    void stringRepresentationsDoNotExposeCredentialsOrTokens() {
        String email = "person@example.test";
        String password = "secret-password-006";
        String access = "secret-access-006";
        String refresh = "secret-refresh-006";

        for (Object value : java.util.List.of(
                new LoginRequest(email, password),
                new ValidatedCredentials(email, password),
                new LoginTokenResponse(access, refresh, "Bearer", 900, 2592000),
                new RefreshRequest(refresh),
                new RefreshResponse(access, refresh, "Bearer", 900, 2592000))) {
            assertThat(value.toString()).doesNotContain(email, password, access, refresh);
        }
    }
}
