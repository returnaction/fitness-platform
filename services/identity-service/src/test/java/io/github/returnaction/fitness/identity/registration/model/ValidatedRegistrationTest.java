package io.github.returnaction.fitness.identity.registration.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import io.github.returnaction.fitness.identity.shared.model.ValidatedCredentials;

class ValidatedRegistrationTest {
    @Test
    void toStringDoesNotExposeEmailOrPassword() {
        var input = new ValidatedCredentials("private@example.com", "secret-password");
        assertThat(input.toString()).doesNotContain("private@example.com", "secret-password");
    }
}
