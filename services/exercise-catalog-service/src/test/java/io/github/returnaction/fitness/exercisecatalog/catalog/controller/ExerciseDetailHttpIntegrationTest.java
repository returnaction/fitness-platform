package io.github.returnaction.fitness.exercisecatalog.catalog.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class ExerciseDetailHttpIntegrationTest {

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

    @Test
    void returnsExerciseWithCategoryMetricUnitAndActiveVariations() throws Exception {
        mvc.perform(get(PATH + "/2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(2))
            .andExpect(jsonPath("$.code").value("PUSH_UP"))
            .andExpect(jsonPath("$.name").value("Отжимания"))
            .andExpect(jsonPath("$.category.id").value(1))
            .andExpect(jsonPath("$.category.code").value("BODYWEIGHT"))
            .andExpect(jsonPath("$.primaryMetric").value("REPETITIONS"))
            .andExpect(jsonPath("$.unit").value("REP"))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.variations.length()").value(5))
            .andExpect(jsonPath("$.variations[0].code").value("STANDARD"))
            .andExpect(jsonPath("$.variations[0].minTarget").value(1))
            .andExpect(jsonPath("$.variations[0].maxTarget").value(150))
            .andExpect(jsonPath("$.variations[0].targetStep").value(1))
            .andExpect(jsonPath("$.variations[0].instructions").isNotEmpty())
            .andExpect(jsonPath("$.variations[1].code").value("ELEVATED_FEET"))
            .andExpect(jsonPath("$.variations[4].code").value("KNEE"));

        mvc.perform(get(PATH + "/6"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("PLANK"))
            .andExpect(jsonPath("$.primaryMetric").value("DURATION"))
            .andExpect(jsonPath("$.unit").value("SECOND"))
            .andExpect(jsonPath("$.variations.length()").value(1));
    }

    @Test
    void returns404ForUnknownInactiveExerciseOrInactiveCategory() throws Exception {
        mvc.perform(get(PATH + "/999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("not_found"))
            .andExpect(jsonPath("$.error.requestId").isNotEmpty());

        insertExercise(70, 1, "INACTIVE_EX", false, 90);
        mvc.perform(get(PATH + "/70"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("not_found"));

        jdbc.update("""
            INSERT INTO catalog_categories (id, code, name, is_active, sort_order, version)
            VALUES (70, 'HIDDEN_CATEGORY', 'Скрытая', false, 90, 1)
            """);
        insertExercise(71, 70, "IN_HIDDEN_CATEGORY", true, 91);
        mvc.perform(get(PATH + "/71"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void excludesInactiveVariations() throws Exception {
        insertExercise(72, 1, "PARTIALLY_ACTIVE", true, 92);
        insertVariation(72, 72, "STANDARD", true, 10);
        insertVariation(73, 72, "NARROW", false, 20);

        mvc.perform(get(PATH + "/72"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.variations.length()").value(1))
            .andExpect(jsonPath("$.variations[0].code").value("STANDARD"));
    }

    @Test
    void rejectsNonNumericIdAsInvalidRequest() throws Exception {
        mvc.perform(get(PATH + "/abc"))
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

    private void insertVariation(int id, int exerciseId, String code, boolean active, int sortOrder) {
        jdbc.update("""
            INSERT INTO catalog_variations
                (id, exercise_id, code, name, description, instructions, min_target, max_target,
                 target_step, is_active, sort_order, version)
            VALUES (?, ?, ?, ?, ?, ?, 1, 100, 1, ?, ?, 1)
            """, id, exerciseId, code, code, code, code, active, sortOrder);
    }
}
