package io.github.returnaction.fitness.identity.session.model;

import io.github.returnaction.fitness.identity.registration.entity.user.UserStatus;
import java.util.UUID;

/** Данные, необходимые для входа; хеш пароля не выводится в логах. */
public record LoginUser(UUID id,
                        UserStatus status,
                        String passwordHash) {

    @Override
    public String toString() {
        return "LoginUser[redacted]";
    }
}
