package io.github.returnaction.fitness.challenge.idempotency;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.challenge.shared.exception.BusinessConflictException;
import io.github.returnaction.fitness.challenge.shared.exception.InvalidRequestException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class IdempotencyServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private IdempotencyService service;

    @Test
    void requiresUuidIdempotencyKey() {
        UUID key = UUID.randomUUID();
        assertThat(service.requireKey(key.toString())).isEqualTo(key);

        assertThatThrownBy(() -> service.requireKey(null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.requireKey(" "))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.requireKey("not-a-uuid"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void replaysOnlyTheSameFingerprint() {
        UUID userId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String path = "/api/v1/challenges/drafts";
        byte[] body = "{}".getBytes(UTF_8);
        StoredHttpResponse response = new StoredHttpResponse(201,
                "{\"challengeId\": \"550e8400-e29b-41d4-a716-446655440000\"}",
                "{\"Location\": \"/api/v1/challenges/550e8400-e29b-41d4-a716-446655440000\"}");

        assertThat(service.findReplay(userId, "POST", path, key, body)).isEmpty();

        service.store(userId, "POST", path, key, body, response);
        var replay = service.findReplay(userId, "POST", path, key, body).orElseThrow();
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).contains("challengeId");
        assertThat(replay.headers()).contains("Location");

        byte[] differentBody = "{\"title\":\"different\"}".getBytes(UTF_8);
        assertThatThrownBy(() -> service.findReplay(userId, "POST", path, key, differentBody))
                .isInstanceOf(BusinessConflictException.class)
                .hasFieldOrPropertyWithValue("code", "idempotency_key_conflict");
    }

    @Test
    void separatesTheSameKeyByPathAndUser() {
        UUID userId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        byte[] body = "{}".getBytes(UTF_8);
        StoredHttpResponse response = new StoredHttpResponse(200, "{}", "{}");
        service.store(userId, "POST", "/api/v1/challenges/drafts", key, body, response);

        assertThat(service.findReplay(userId, "POST", "/api/v1/challenges/other/publish", key, body)).isEmpty();
        assertThat(service.findReplay(UUID.randomUUID(), "POST", "/api/v1/challenges/drafts", key, body)).isEmpty();

        assertThatThrownBy(() -> service.store(userId, "POST", "/api/v1/challenges/drafts", key, body, response))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
