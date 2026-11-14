package io.github.returnaction.fitness.exercisecatalog.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class CatalogSchemaMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void appliesMigrationsOnceWithTablesAndIndexes() throws Exception {
        applyMigrations();
        applyMigrations();

        try (Connection connection = connection()) {
            assertThat(changeSetCount(connection)).isEqualTo(2);
            assertThat(tableExists(connection, "catalog_categories")).isTrue();
            assertThat(columnCount(connection, "catalog_categories")).isEqualTo(8);
            assertThat(indexDefinition(connection, "catalog_categories_active_order_index"))
                .contains("(is_active, sort_order, id)");

            assertThat(tableExists(connection, "catalog_exercises")).isTrue();
            assertThat(columnCount(connection, "catalog_exercises")).isEqualTo(11);
            assertThat(indexDefinition(connection, "catalog_exercises_category_active_order_index"))
                .contains("(category_id, is_active, sort_order, id)");
        }
    }

    @Test
    void rejectsInvalidCategoryRows() throws Exception {
        applyMigrations();

        try (Connection connection = connection()) {
            assertThatCode(() -> insertCategory(connection, 1, "BODYWEIGHT", 10, 1))
                .doesNotThrowAnyException();

            assertThatThrownBy(() -> insertCategory(connection, 2, "BODYWEIGHT", 20, 1))
                .as("duplicate code")
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertCategory(connection, 0, "ZERO_ID", 0, 1))
                .as("id below range")
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertCategory(connection, 32768, "TOO_BIG_ID", 0, 1))
                .as("id above range")
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertCategory(connection, 3, "NEGATIVE_ORDER", -1, 1))
                .as("negative sort_order")
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertCategory(connection, 4, "ZERO_VERSION", 0, 0))
                .as("zero version")
                .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void rejectsInvalidExerciseRows() throws Exception {
        applyMigrations();

        try (Connection connection = connection()) {
            // Свои ID, чтобы не пересекаться с данными других методов в общем контейнере.
            insertCategory(connection, 100, "BODYWEIGHT_TEST", 10, 1);
            assertThatCode(() -> insertExercise(connection, 100, 100, "SQUAT", "REPETITIONS", 10, 1))
                .doesNotThrowAnyException();

            assertThatThrownBy(() -> insertExercise(connection, 101, 999, "MISSING_CATEGORY", "REPETITIONS", 0, 1))
                .as("category does not exist")
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertExercise(connection, 102, 100, "SQUAT", "REPETITIONS", 20, 1))
                .as("duplicate code")
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertExercise(connection, 0, 100, "ZERO_ID", "REPETITIONS", 0, 1))
                .as("id below range")
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertExercise(connection, 32768, 100, "TOO_BIG_ID", "REPETITIONS", 0, 1))
                .as("id above range")
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertExercise(connection, 104, 100, "UNKNOWN_METRIC", "PACE", 0, 1))
                .as("unsupported metric")
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertExercise(connection, 105, 100, "NEGATIVE_ORDER", "REPETITIONS", -1, 1))
                .as("negative sort_order")
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> deleteCategory(connection, 100))
                .as("category still has an exercise")
                .isInstanceOf(SQLException.class);
        }
    }

    private void applyMigrations() throws Exception {
        try (Connection connection = connection()) {
            Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.yaml",
                new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void insertCategory(Connection connection, int id, String code, int sortOrder, long version)
        throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO catalog_categories (id, code, name, is_active, sort_order, version)
            VALUES (?, ?, ?, true, ?, ?)
            """)) {
            statement.setInt(1, id);
            statement.setString(2, code);
            statement.setString(3, code);
            statement.setInt(4, sortOrder);
            statement.setLong(5, version);
            statement.executeUpdate();
        }
    }

    private void insertExercise(Connection connection, int id, int categoryId, String code, String metric,
        int sortOrder, long version) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO catalog_exercises
                (id, category_id, code, name, description, primary_metric, is_active, sort_order, version)
            VALUES (?, ?, ?, ?, ?, ?, true, ?, ?)
            """)) {
            statement.setInt(1, id);
            statement.setInt(2, categoryId);
            statement.setString(3, code);
            statement.setString(4, code);
            statement.setString(5, code);
            statement.setString(6, metric);
            statement.setInt(7, sortOrder);
            statement.setLong(8, version);
            statement.executeUpdate();
        }
    }

    private void deleteCategory(Connection connection, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM catalog_categories WHERE id = ?")) {
            statement.setInt(1, id);
            statement.executeUpdate();
        }
    }

    private int changeSetCount(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM databasechangelog");
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT count(*) FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name = ?
            """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) == 1;
            }
        }
    }

    private int columnCount(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT count(*) FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ?
            """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private String indexDefinition(Connection connection, String index) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?
            """)) {
            statement.setString(1, index);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return "";
                }
                return result.getString(1);
            }
        }
    }
}
