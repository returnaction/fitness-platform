package io.github.returnaction.fitness.exercisecatalog.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.returnaction.fitness.exercisecatalog.catalog.service.ExerciseCursorCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExerciseListHttpIntegrationTest {

    private static final String PATH = "/api/v1/exercises";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExerciseCursorCodec cursorCodec;

    @Test
    void paginatesAndReturnsOnlyActiveExercisesWithActiveStandard() throws Exception {
        insertExercise(60, 1, "INACTIVE_EX", false, 5);
        insertExercise(61, 1, "NO_STANDARD", true, 6);
        insertExercise(62, 1, "ACTIVE_STANDARD", true, 7);
        insertExercise(63, 1, "INACTIVE_STANDARD", true, 8);
        insertVariation(62, 62, "STANDARD", true);
        insertVariation(63, 63, "STANDARD", false);

        var first = mvc.perform(get(PATH).param("limit", "3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.items[0].code").value("SQUAT"))
            .andExpect(jsonPath("$.items[0].primaryMetric").value("REPETITIONS"))
            .andExpect(jsonPath("$.items[1].code").value("JUMP_ROPE"))
            .andExpect(jsonPath("$.items[2].code").value("PUSH_UP"))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty())
            .andReturn();
        String firstCursor = first.getResponse().getContentAsString()
            .replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");
        assertThat(firstCursor).isNotBlank();

        var second = mvc.perform(get(PATH).param("limit", "3").param("cursor", firstCursor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.items[0].code").value("BURPEE"))
            .andExpect(jsonPath("$.items[1].code").value("JUMPING_JACK"))
            .andExpect(jsonPath("$.items[2].code").value("PLANK"))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty())
            .andReturn();
        String secondCursor = second.getResponse().getContentAsString()
            .replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");

        mvc.perform(get(PATH).param("limit", "3").param("cursor", secondCursor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].code").value("ACTIVE_STANDARD"))
            .andExpect(jsonPath("$.nextCursor").value(nullValue()));

        mvc.perform(get(PATH).param("category_id", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].code").value("JUMP_ROPE"));

        mvc.perform(get(PATH).param("limit", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.code == 'INACTIVE_EX')]").isEmpty())
            .andExpect(jsonPath("$.items[?(@.code == 'NO_STANDARD')]").isEmpty())
            .andExpect(jsonPath("$.items[?(@.code == 'INACTIVE_STANDARD')]").isEmpty());
    }

    @Test
    void rejectsInvalidLimitCursorAndTypeMismatch() throws Exception {
        mvc.perform(get(PATH).param("limit", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("invalid_request"));
        mvc.perform(get(PATH).param("limit", "101"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("invalid_request"));
        mvc.perform(get(PATH).param("cursor", "not-a-cursor"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("invalid_request"));
        mvc.perform(get(PATH).param("category_id", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("invalid_request"));
        mvc.perform(get(PATH).param("category_id", "2").param("cursor", cursorCodec.encode(1, 10, 1)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    private void insertExercise(int id, int categoryId, String code, boolean active, int sortOrder) {
        jdbc.update("""
            INSERT INTO catalog_exercises
                (id, category_id, code, name, description, primary_metric, is_active, sort_order, version)
            VALUES (?, ?, ?, ?, ?, 'REPETITIONS', ?, ?, 1)
            """, id, categoryId, code, code, code, active, sortOrder);
    }

    private void insertVariation(int id, int exerciseId, String code, boolean active) {
        jdbc.update("""
            INSERT INTO catalog_variations
                (id, exercise_id, code, name, description, instructions, min_target, max_target,
                 target_step, is_active, sort_order, version)
            VALUES (?, ?, ?, ?, ?, ?, 1, 100, 1, ?, 10, 1)
            """, id, exerciseId, code, code, code, code, active);
    }
}
