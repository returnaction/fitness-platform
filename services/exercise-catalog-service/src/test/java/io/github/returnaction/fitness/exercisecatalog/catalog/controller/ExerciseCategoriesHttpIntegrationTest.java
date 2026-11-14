package io.github.returnaction.fitness.exercisecatalog.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseCategoryResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * HTTP-интеграционный тест публичного API категорий на реальном PostgreSQL:
 * только активные записи, в порядке сортировки; остальные запросы закрыты.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class ExerciseCategoriesHttpIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.autoconfigure.exclude", () -> "");
        registry.add("spring.liquibase.enabled", () -> "true");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void returnsOnlyActiveCategoriesInSortOrder() {
        jdbcTemplate.update(
                "INSERT INTO catalog_categories (id, code, name, is_active, sort_order, version) "
                        + "VALUES (999, 'DISABLED_TEST', 'Неактивная тестовая', false, 5, 1)");

        ResponseEntity<List<ExerciseCategoryResponse>> response = restTemplate.exchange(
                url("/api/v1/exercise-categories"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<ExerciseCategoryResponse> categories = response.getBody();
        // Неактивная DISABLED_TEST отсутствует, порядок по (sort_order, id).
        assertThat(categories).extracting(ExerciseCategoryResponse::code)
                .containsExactly("BODYWEIGHT", "CARDIO");
        assertThat(categories).extracting(ExerciseCategoryResponse::sortOrder)
                .containsExactly(10, 20);
    }

    @Test
    void rejectsNonGetAccessToCatalog() {
        ResponseEntity<Void> post = restTemplate.exchange(
                url("/api/v1/exercise-categories"),
                HttpMethod.POST,
                new HttpEntity<>(new byte[0]),
                Void.class);
        assertThat(post.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<Void> unknown = restTemplate.getForEntity(url("/unknown"), Void.class);
        assertThat(unknown.getStatusCode().value()).isEqualTo(403);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}

