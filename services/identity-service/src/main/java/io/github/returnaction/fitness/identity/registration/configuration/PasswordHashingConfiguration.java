package io.github.returnaction.fitness.identity.registration.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Создаёт единый Argon2id encoder для хеширования и проверки паролей Identity. */
@Configuration
@EnableConfigurationProperties(Argon2PasswordProperties.class)
public class PasswordHashingConfiguration {

    @Bean
    PasswordEncoder passwordEncoder(Argon2PasswordProperties properties) {
        return new Argon2PasswordEncoder(
                properties.saltLength(),
                properties.hashLength(),
                properties.parallelism(),
                properties.memoryKib(),
                properties.iterations());
    }
}
