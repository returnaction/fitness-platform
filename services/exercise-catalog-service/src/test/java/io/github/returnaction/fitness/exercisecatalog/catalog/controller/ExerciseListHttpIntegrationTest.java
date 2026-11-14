package io.github.returnaction.fitness.exercisecatalog.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseListItem;
import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseListResponse;
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
 * HTTP-интеграционный тест списка упражнений на реальном PostgreSQL:
 * только активные с активным STANDARD, фильтр категории, limit,
 * ключевая пагинация по курсору, 400 для неверного курсора и лимита.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class ExerciseListHttpIntegrationTest {

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

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void returnsAllActiveExercisesWithStandardOnly() {
        // Упражнение без активной STANDARD не попадает в список.
        jdbcTemplate.update(
                "INSERT INTO catalog_exercises (id, category_id, code, name, description, primary_metric, "
                        + "is_active, sort_order, version) "
                        + "VALUES (500, 1, 'NO_STANDARD', 'Без стандартной вариации', 'Описание.', "
                        + "'REPETITIONS', true, 60, 1)");

        ResponseEntity<ExerciseListResponse> response = restTemplate.exchange(
                url("/api/v1/exercises"), HttpMethod.GET, null, ExerciseListResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().items()).extracting(ExerciseListItem::code)
                .containsExactly("SQUAT", "JUMP_ROPE", "PUSH_UP", "BURPEE", "JUMPING_JACK", "PLANK");
    }

    @Test
    void filtersByCategory() {
        ResponseEntity<ExerciseListResponse> response = restTemplate.exchange(
                url("/api/v1/exercises?category_id=2"), HttpMethod.GET, null, ExerciseListResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().items()).extracting(ExerciseListItem::code)
                .containsExactly("JUMP_ROPE");
        assertThat(response.getBody().nextCursor()).isNull();
    }

    @Test
    void pagesThroughResultsWithCursor() {
        ResponseEntity<ExerciseListResponse> first = restTemplate.exchange(
                url("/api/v1/exercises?limit=2"), HttpMethod.GET, null, ExerciseListResponse.class);

        Assertions.assertNotNull(first.getBody());
        assertThat(first.getBody().items()).extracting(ExerciseListItem::code)
                .containsExactly("SQUAT", "JUMP_ROPE");
        assertThat(first.getBody().nextCursor()).isNotNull();

        ResponseEntity<ExerciseListResponse> second = restTemplate.exchange(
                url("/api/v1/exercises?limit=2&cursor=" + first.getBody().nextCursor()),
                HttpMethod.GET, null, ExerciseListResponse.class);

        Assertions.assertNotNull(second.getBody());
        assertThat(second.getBody().items()).extracting(ExerciseListItem::code)
                .containsExactly("PUSH_UP", "BURPEE");
        assertThat(second.getBody().nextCursor()).isNotNull();

        ResponseEntity<ExerciseListResponse> third = restTemplate.exchange(
                url("/api/v1/exercises?limit=2&cursor=" + second.getBody().nextCursor()),
                HttpMethod.GET, null, ExerciseListResponse.class);

        Assertions.assertNotNull(third.getBody());
        assertThat(third.getBody().items()).extracting(ExerciseListItem::code)
                .containsExactly("JUMPING_JACK", "PLANK");
        // Шестой элемент списка — JUMP_ROPE уже на первой странице; NO_STANDARD отфильтрован.
        assertThat(third.getBody().nextCursor()).isNull();
    }

    @Test
    void cursorIsBoundToCategoryFilter() {
        // Курсор страницы категории 1 нельзя использовать с фильтром категории 2.
        ResponseEntity<ExerciseListResponse> first = restTemplate.exchange(
                url("/api/v1/exercises?category_id=1&limit=2"), HttpMethod.GET, null, ExerciseListResponse.class);
        Assertions.assertNotNull(first.getBody());
        String cursor = first.getBody().nextCursor();
        assertThat(cursor).isNotNull();

        ResponseEntity<String> mismatch = restTemplate.exchange(
                url("/api/v1/exercises?category_id=2&cursor=" + cursor),
                HttpMethod.GET, null, String.class);
        assertThat(mismatch.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<String> garbage = restTemplate.exchange(
                url("/api/v1/exercises?cursor=not-a-cursor"), HttpMethod.GET, null, String.class);
        assertThat(garbage.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void rejectsInvalidLimit() {
        ResponseEntity<String> tooBig = restTemplate.exchange(
                url("/api/v1/exercises?limit=101"), HttpMethod.GET, null, String.class);
        assertThat(tooBig.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<String> tooSmall = restTemplate.exchange(
                url("/api/v1/exercises?limit=0"), HttpMethod.GET, null, String.class);
        assertThat(tooSmall.getStatusCode().value()).isEqualTo(400);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
