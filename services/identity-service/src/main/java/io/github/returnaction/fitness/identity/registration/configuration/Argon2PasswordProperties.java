package io.github.returnaction.fitness.identity.registration.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Параметры Argon2id, закреплённые спецификацией регистрации первой версии. */
@ConfigurationProperties("identity.registration.password.argon2")
public record Argon2PasswordProperties(
        int saltLength, int hashLength, int parallelism, int memoryKib, int iterations) {}
