package io.github.returnaction.fitness.exercisecatalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL-интеграционный тест миграций каталога: схема, ограничения
 * и идемпотентность применения Liquibase.
 */
@Testcontainers(disabledWithoutDocker = true)
class CatalogMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private Connection connection;

    @BeforeAll
    static void applyMigrations() throws Exception {
        runMigrations();
    }

    @BeforeEach
    void openConnection() throws SQLException {
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.setAutoCommit(false);
    }

    @AfterEach
    void rollbackAndCloseConnection() throws SQLException {
        if (connection != null) {
            connection.rollback();
            connection.close();
        }
    }

    @Test
    void createsCatalogTablesAndIndexes() throws SQLException {
        assertThat(tableNames())
                .contains(
                        "catalog_categories",
                        "catalog_exercises",
                        "catalog_variations");
        assertThat(indexNames())
                .contains(
                        "catalog_categories_active_sort_idx",
                        "catalog_exercises_category_active_sort_idx",
                        "catalog_variations_exercise_active_sort_idx");
        try (ResultSet result = connection.createStatement().executeQuery(
                "SELECT count(*) FROM databasechangelog")) {
            result.next();
            assertThat(result.getInt(1)).isEqualTo(4);
        }
    }

    @Test
    void rejectsInvalidCategories() throws SQLException {
        expectRejected(
                "INSERT INTO catalog_categories (id, code, name, is_active, sort_order, version) "
                        + "VALUES (1, 'BODYWEIGHT', 'Дубликат seed-категории', true, 10, 1)");
        expectRejected(
                "INSERT INTO catalog_categories (id, code, name, is_active, sort_order, version) "
                        + "VALUES (32768, 'ID_OVERFLOW', 'Вне диапазона SMALLINT', true, 10, 1)");
        expectRejected(
                "INSERT INTO catalog_categories (code, name, is_active, sort_order, version) "
                        + "VALUES ('NEGATIVE_SORT', 'Отрицательный порядок', true, -1, 1)");
        expectRejected(
                "INSERT INTO catalog_categories (code, name, is_active, sort_order, version) "
                        + "VALUES ('ZERO_VERSION', 'Нулевая версия', true, 10, 0)");
    }

    @Test
    void rejectsInvalidExercises() throws SQLException {
        expectRejected(
                "INSERT INTO catalog_exercises (id, category_id, code, name, description, primary_metric, "
                        + "is_active, sort_order, version) "
                        + "VALUES (1, 999, 'UNKNOWN_CATEGORY', 'Без категории', 'Описание.', 'REPETITIONS', true, 10, 1)");
        expectRejected(
                "INSERT INTO catalog_exercises (id, category_id, code, name, description, primary_metric, "
                        + "is_active, sort_order, version) "
                        + "VALUES (1, 1, 'SQUAT', 'Дубликат seed-упражнения', 'Описание.', 'REPETITIONS', true, 10, 1)");
        expectRejected(
                "INSERT INTO catalog_exercises (id, category_id, code, name, description, primary_metric, "
                        + "is_active, sort_order, version) "
                        + "VALUES (32768, 1, 'ID_OVERFLOW', 'Вне диапазона SMALLINT', 'Описание.', 'REPETITIONS', true, 10, 1)");
        expectRejected(
                "INSERT INTO catalog_exercises (id, category_id, code, name, description, primary_metric, "
                        + "is_active, sort_order, version) "
                        + "VALUES (1, 1, 'BAD_METRIC', 'Недопустимая метрика', 'Описание.', 'CALORIES', true, 10, 1)");
        expectRejected(
                "INSERT INTO catalog_exercises (id, category_id, code, name, description, primary_metric, "
                        + "is_active, sort_order, version) "
                        + "VALUES (1, 1, 'NEGATIVE_SORT', 'Отрицательный порядок', 'Описание.', 'REPETITIONS', true, -1, 1)");
    }

    @Test
    void restrictsCategoryDeletionWithExercises() throws SQLException {
        executeUpdate(
                "INSERT INTO catalog_exercises (id, category_id, code, name, description, primary_metric, "
                        + "is_active, sort_order, version) "
                        + "VALUES (100, 1, 'TEMPORARY', 'Временное упражнение', 'Описание.', 'REPETITIONS', true, 10, 1)");
        assertThatThrownBy(() -> executeUpdate("DELETE FROM catalog_categories WHERE id = 1"))
                .isInstanceOf(SQLException.class);
        connection.rollback();
        assertThat(count("catalog_exercises", 100)).isZero();
    }

    @Test
    void rejectsInvalidVariations() throws SQLException {
        expectRejected(
                "INSERT INTO catalog_variations (id, exercise_id, code, name, description, instructions, "
                        + "min_target, max_target, target_step, is_active, sort_order, version) "
                        + "VALUES (1, 999, 'UNKNOWN_EXERCISE', 'Без упражнения', 'Описание.', 'Инструкция.', "
                        + "1, 100, 1, true, 10, 1)");
        expectRejected(
                "INSERT INTO catalog_variations (id, exercise_id, code, name, description, instructions, "
                        + "min_target, max_target, target_step, is_active, sort_order, version) "
                        + "VALUES (1, 1, 'STANDARD', 'Дубликат seed-вариации', 'Описание.', 'Инструкция.', "
                        + "1, 100, 1, true, 10, 1)");
        expectRejected(
                "INSERT INTO catalog_variations (id, exercise_id, code, name, description, instructions, "
                        + "min_target, max_target, target_step, is_active, sort_order, version) "
                        + "VALUES (32768, 1, 'ID_OVERFLOW', 'Вне диапазона SMALLINT', 'Описание.', 'Инструкция.', "
                        + "1, 100, 1, true, 10, 1)");
        expectRejected(
                "INSERT INTO catalog_variations (id, exercise_id, code, name, description, instructions, "
                        + "min_target, max_target, target_step, is_active, sort_order, version) "
                        + "VALUES (1, 1, 'ZERO_MIN', 'Нулевой минимум', 'Описание.', 'Инструкция.', "
                        + "0, 100, 1, true, 10, 1)");
        expectRejected(
                "INSERT INTO catalog_variations (id, exercise_id, code, name, description, instructions, "
                        + "min_target, max_target, target_step, is_active, sort_order, version) "
                        + "VALUES (1, 1, 'MAX_BELOW_MIN', 'Максимум меньше минимума', 'Описание.', 'Инструкция.', "
                        + "100, 50, 1, true, 10, 1)");
        expectRejected(
                "INSERT INTO catalog_variations (id, exercise_id, code, name, description, instructions, "
                        + "min_target, max_target, target_step, is_active, sort_order, version) "
                        + "VALUES (1, 1, 'ZERO_STEP', 'Нулевой шаг', 'Описание.', 'Инструкция.', "
                        + "1, 100, 0, true, 10, 1)");
        expectRejected(
                "INSERT INTO catalog_variations (id, exercise_id, code, name, description, instructions, "
                        + "min_target, max_target, target_step, is_active, sort_order, version) "
                        + "VALUES (1, 1, 'NEGATIVE_SORT', 'Отрицательный порядок', 'Описание.', 'Инструкция.', "
                        + "1, 100, 1, true, -1, 1)");
    }

    @Test
    void keepsSeedDataAfterRerun() throws Exception {
        assertThat(count("catalog_categories")).isEqualTo(2);
        assertThat(count("catalog_exercises")).isEqualTo(6);
        assertThat(count("catalog_variations")).isEqualTo(12);
        runMigrations();
        assertThat(count("catalog_categories")).isEqualTo(2);
        assertThat(count("catalog_exercises")).isEqualTo(6);
        assertThat(count("catalog_variations")).isEqualTo(12);
    }

    private void expectRejected(String sql) throws SQLException {
        assertThatThrownBy(() -> executeUpdate(sql)).isInstanceOf(SQLException.class);
        // Ошибка переводит транзакцию в aborted: откатываем перед следующей попыткой,
        // чтобы каждая проверка ловила именно своё нарушение, а не abort предыдущей.
        connection.rollback();
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

    private int count(String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private int count(String table, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM " + table + " WHERE id = ?")) {
            statement.setInt(1, id);
            ResultSet result = statement.executeQuery();
            result.next();
            return result.getInt(1);
        }
    }

    private List<String> tableNames() throws SQLException {
        return stringColumn("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'");
    }

    private List<String> indexNames() throws SQLException {
        return stringColumn("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'");
    }

    private List<String> stringColumn(String query) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(query)) {
            List<String> names = new ArrayList<>();
            while (result.next()) {
                names.add(result.getString(1));
            }
            return names;
        }
    }

    private void executeUpdate(String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }
}
