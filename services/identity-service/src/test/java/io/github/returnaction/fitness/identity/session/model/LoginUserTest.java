package io.github.returnaction.fitness.identity.session.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.returnaction.fitness.identity.registration.entity.user.UserStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoginUserTest {

    @Test
    void toStringDoesNotExposePasswordHash() {
        var user = new LoginUser(UUID.randomUUID(), UserStatus.ACTIVE, "secret-hash");
        assertThat(user.toString()).doesNotContain("secret-hash");
    }
}
