package io.github.returnaction.fitness.exercisecatalog.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseDetailResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseVariationResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * HTTP-интеграционный тест карточки упражнения на реальном PostgreSQL:
 * метрика с единицей, активные вариации с инструкцией и пределами,
 * 404 для неизвестного или выключенного упражнения.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class ExerciseDetailHttpIntegrationTest {

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
    void returnsDetailWithActiveVariationsAndUnit() {
        ResponseEntity<ExerciseDetailResponse> response = restTemplate.getForEntity(
                url("/api/v1/exercises/2"), ExerciseDetailResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ExerciseDetailResponse detail = response.getBody();
        Assertions.assertNotNull(detail);
        assertThat(detail.code()).isEqualTo("PUSH_UP");
        assertThat(detail.primaryMetric()).isEqualTo("REPETITIONS");
        assertThat(detail.unit()).isEqualTo("REP");
        // Только активные вариации, в порядке (sort_order, id), с инструкцией и пределами.
        assertThat(detail.variations()).extracting(ExerciseVariationResponse::code)
                .containsExactly("STANDARD", "ELEVATED_FEET", "NARROW", "WIDE", "KNEE");
        assertThat(detail.variations()).allSatisfy(v -> {
            assertThat(v.minTarget()).isPositive();
            assertThat(v.maxTarget()).isGreaterThanOrEqualTo(v.minTarget());
            assertThat(v.targetStep()).isPositive();
            assertThat(v.instructions()).isNotBlank();
        });
    }

    @Test
    void returnsSecondUnitForDurationExercise() {
        ResponseEntity<ExerciseDetailResponse> response = restTemplate.getForEntity(
                url("/api/v1/exercises/6"), ExerciseDetailResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().primaryMetric()).isEqualTo("DURATION");
        assertThat(response.getBody().unit()).isEqualTo("SECOND");
    }

    @Test
    void returnsNotFoundForUnknownId() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/exercises/999"), HttpMethod.GET, null, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void returnsNotFoundForInactiveExercise() {
        jdbcTemplate.update(
                "INSERT INTO catalog_exercises (id, category_id, code, name, description, primary_metric, "
                        + "is_active, sort_order, version) "
                        + "VALUES (600, 1, 'INACTIVE_TEST', 'Выключенное тестовое', 'Описание.', "
                        + "'REPETITIONS', false, 90, 1)");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/exercises/600"), HttpMethod.GET, null, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
