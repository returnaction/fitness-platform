package io.github.returnaction.fitness.exercisecatalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL-интеграционный тест seed-данных: состав соответствует
 * утверждённой таблице стартового каталога из задач T008.
 */
@Testcontainers(disabledWithoutDocker = true)
class CatalogSeedDataIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void applyMigrations() throws Exception {
        runMigrations();
    }

    @Test
    void seedsApprovedCatalogData() throws SQLException {
        assertThat(categoryRows()).containsExactlyInAnyOrderElementsOf(expectedCategories().values());
        assertThat(exerciseRows()).containsExactlyInAnyOrderElementsOf(expectedExercises().values());
        assertThat(variationRows()).containsExactlyInAnyOrderElementsOf(expectedVariations().values());
    }

    @Test
    void everyActiveExerciseHasOneActiveStandardVariation() throws SQLException {
        Map<Integer, List<String>> codesByExercise = variationCodesByExercise();
        for (Integer exerciseId : activeExerciseIds()) {
            List<String> codes = codesByExercise.get(exerciseId);
            assertThat(codes).isNotNull();
            // Ровно одна активная STANDARD; другие активные вариации допустимы.
            assertThat(codes.stream().filter("STANDARD"::equals).count()).isEqualTo(1);
        }
    }

    @Test
    void rejectsTargetStepZeroOnSeedData() {
        // Правило target_step > 0 действует и на утверждённые данные.
        assertThatThrownBy(() -> queryOne(
                "UPDATE catalog_variations SET target_step = 0 WHERE id = 1"))
                .isInstanceOf(SQLException.class);
    }

    private static void runMigrations() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private List<Map<String, Object>> categoryRows() throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (ResultSet result = connection().createStatement().executeQuery(
                "SELECT id, code, name, is_active, sort_order, version FROM catalog_categories ORDER BY id")) {
            while (result.next()) {
                Map<String, Object> values = new HashMap<>();
                values.put("id", result.getInt("id"));
                values.put("code", result.getString("code"));
                values.put("name", result.getString("name"));
                values.put("is_active", result.getBoolean("is_active"));
                values.put("sort_order", result.getInt("sort_order"));
                values.put("version", result.getLong("version"));
                rows.add(values);
            }
        }
        return rows;
    }

    private Map<Integer, Map<String, Object>> expectedCategories() {
        Map<Integer, Map<String, Object>> expected = new HashMap<>();
        expected.put(1, category(1, "BODYWEIGHT", "Упражнения с собственным весом", 10));
        expected.put(2, category(2, "CARDIO", "Кардио", 20));
        return expected;
    }

    private List<Map<String, Object>> exerciseRows() throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (ResultSet result = connection().createStatement().executeQuery(
                "SELECT id, category_id, code, name, description, primary_metric, is_active, sort_order, version "
                        + "FROM catalog_exercises ORDER BY id")) {
            while (result.next()) {
                Map<String, Object> values = new HashMap<>();
                values.put("id", result.getInt("id"));
                values.put("category_id", result.getInt("category_id"));
                values.put("code", result.getString("code"));
                values.put("name", result.getString("name"));
                values.put("description", result.getString("description"));
                values.put("primary_metric", result.getString("primary_metric"));
                values.put("is_active", result.getBoolean("is_active"));
                values.put("sort_order", result.getInt("sort_order"));
                values.put("version", result.getLong("version"));
                rows.add(values);
            }
        }
        return rows;
    }

    private Map<Integer, Map<String, Object>> expectedExercises() {
        Map<Integer, Map<String, Object>> expected = new HashMap<>();
        expected.put(1, exercise(1, 1, "SQUAT", "Приседания", "Приседания с собственным весом.", "REPETITIONS", 10));
        expected.put(2, exercise(2, 1, "PUSH_UP", "Отжимания", "Отжимания в выбранном варианте.", "REPETITIONS", 20));
        expected.put(3, exercise(3, 1, "BURPEE", "Бёрпи", "Бёрпи в выбранном варианте.", "REPETITIONS", 30));
        expected.put(4, exercise(4, 1, "JUMPING_JACK", "Прыжки с разведением рук и ног",
                "Прыжки с одновременным разведением рук и ног.", "REPETITIONS", 40));
        expected.put(5, exercise(5, 2, "JUMP_ROPE", "Прыжки со скакалкой", "Прыжки через скакалку.", "REPETITIONS", 10));
        expected.put(6, exercise(6, 1, "PLANK", "Планка", "Статическое удержание положения планки.", "DURATION", 50));
        return expected;
    }

    private List<Map<String, Object>> variationRows() throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (ResultSet result = connection().createStatement().executeQuery(
                "SELECT id, exercise_id, code, name, min_target, max_target, target_step, is_active, sort_order, version "
                        + "FROM catalog_variations ORDER BY id")) {
            while (result.next()) {
                Map<String, Object> values = new HashMap<>();
                values.put("id", result.getInt("id"));
                values.put("exercise_id", result.getInt("exercise_id"));
                values.put("code", result.getString("code"));
                values.put("name", result.getString("name"));
                values.put("min_target", result.getLong("min_target"));
                values.put("max_target", result.getLong("max_target"));
                values.put("target_step", result.getLong("target_step"));
                values.put("is_active", result.getBoolean("is_active"));
                values.put("sort_order", result.getInt("sort_order"));
                values.put("version", result.getLong("version"));
                rows.add(values);
            }
        }
        return rows;
    }

    private Map<Integer, Map<String, Object>> expectedVariations() {
        Map<Integer, Map<String, Object>> expected = new HashMap<>();
        expected.put(1, variation(1, 1, "STANDARD", "Обычные", 1, 200, 1, 10));
        expected.put(2, variation(2, 2, "STANDARD", "Обычные", 1, 150, 1, 10));
        expected.put(3, variation(3, 2, "ELEVATED_FEET", "Ноги на возвышении", 1, 100, 1, 20));
        expected.put(4, variation(4, 2, "NARROW", "Узкий хват", 1, 100, 1, 30));
        expected.put(5, variation(5, 2, "WIDE", "Широкий хват", 1, 100, 1, 40));
        expected.put(6, variation(6, 2, "KNEE", "С колен", 1, 150, 1, 50));
        expected.put(7, variation(7, 3, "STANDARD", "Обычные", 1, 100, 1, 10));
        expected.put(8, variation(8, 3, "JUMP", "С прыжком", 1, 75, 1, 20));
        expected.put(9, variation(9, 3, "RAISED_ARMS_JUMP", "С прыжком и поднятыми руками", 1, 50, 1, 30));
        expected.put(10, variation(10, 4, "STANDARD", "Обычные", 1, 300, 1, 10));
        expected.put(11, variation(11, 5, "STANDARD", "Обычные", 10, 1000, 1, 10));
        expected.put(12, variation(12, 6, "STANDARD", "На предплечьях", 10, 600, 1, 10));
        return expected;
    }

    private Map<Integer, List<String>> variationCodesByExercise() throws SQLException {
        Map<Integer, List<String>> codesByExercise = new HashMap<>();
        try (ResultSet result = connection().createStatement().executeQuery(
                "SELECT exercise_id, code, is_active FROM catalog_variations ORDER BY exercise_id, code")) {
            while (result.next()) {
                if (!result.getBoolean("is_active")) {
                    continue;
                }
                codesByExercise
                        .computeIfAbsent(result.getInt("exercise_id"), id -> new ArrayList<>())
                        .add(result.getString("code"));
            }
        }
        return codesByExercise;
    }

    private Set<Integer> activeExerciseIds() throws SQLException {
        Set<Integer> ids = new HashSet<>();
        try (ResultSet result = connection().createStatement().executeQuery(
                "SELECT id FROM catalog_exercises WHERE is_active")) {
            while (result.next()) {
                ids.add(result.getInt("id"));
            }
        }
        assertThat(ids).hasSize(6);
        return ids;
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void queryOne(String sql) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static Map<String, Object> category(
            int id, String code, String name, int sortOrder) {
        Map<String, Object> values = new HashMap<>();
        values.put("id", id);
        values.put("code", code);
        values.put("name", name);
        values.put("is_active", true);
        values.put("sort_order", sortOrder);
        values.put("version", 1L);
        return values;
    }

    private static Map<String, Object> exercise(
            int id, int categoryId, String code, String name, String description,
            String metric, int sortOrder) {
        Map<String, Object> values = new HashMap<>();
        values.put("id", id);
        values.put("category_id", categoryId);
        values.put("code", code);
        values.put("name", name);
        values.put("description", description);
        values.put("primary_metric", metric);
        values.put("is_active", true);
        values.put("sort_order", sortOrder);
        values.put("version", 1L);
        return values;
    }

    private static Map<String, Object> variation(
            int id, int exerciseId, String code, String name, int minTarget, int maxTarget,
            int targetStep, int sortOrder) {
        Map<String, Object> values = new HashMap<>();
        values.put("id", id);
        values.put("exercise_id", exerciseId);
        values.put("code", code);
        values.put("name", name);
        values.put("min_target", (long) minTarget);
        values.put("max_target", (long) maxTarget);
        values.put("target_step", (long) targetStep);
        values.put("is_active", true);
        values.put("sort_order", sortOrder);
        values.put("version", 1L);
        return values;
    }
}
