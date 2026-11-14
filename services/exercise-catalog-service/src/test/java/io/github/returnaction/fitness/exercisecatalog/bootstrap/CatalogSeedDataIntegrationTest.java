package io.github.returnaction.fitness.exercisecatalog.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
class CatalogSeedDataIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void loadsApprovedCatalogOnce() throws Exception {
        applyMigrations();
        applyMigrations();

        try (Connection connection = connection()) {
            assertThat(singleNumber(connection, "SELECT count(*) FROM catalog_categories")).isEqualTo(2);
            assertThat(singleNumber(connection, "SELECT count(*) FROM catalog_exercises")).isEqualTo(6);
            assertThat(singleNumber(connection, "SELECT count(*) FROM catalog_variations")).isEqualTo(12);

            assertThat(queryList(connection, "SELECT id || '|' || code || '|' || sort_order "
                + "FROM catalog_categories ORDER BY id"))
                .containsExactly(
                    "1|BODYWEIGHT|10",
                    "2|CARDIO|20");

            assertThat(queryList(connection, "SELECT id || '|' || category_id || '|' || code || '|' "
                + "|| primary_metric || '|' || sort_order FROM catalog_exercises ORDER BY id"))
                .containsExactly(
                    "1|1|SQUAT|REPETITIONS|10",
                    "2|1|PUSH_UP|REPETITIONS|20",
                    "3|1|BURPEE|REPETITIONS|30",
                    "4|1|JUMPING_JACK|REPETITIONS|40",
                    "5|2|JUMP_ROPE|REPETITIONS|10",
                    "6|1|PLANK|DURATION|50");

            assertThat(queryList(connection, "SELECT id || '|' || exercise_id || '|' || code || '|' "
                + "|| min_target || '|' || max_target || '|' || target_step || '|' || sort_order "
                + "FROM catalog_variations ORDER BY id"))
                .containsExactly(
                    "1|1|STANDARD|1|200|1|10",
                    "2|2|STANDARD|1|150|1|10",
                    "3|2|ELEVATED_FEET|1|100|1|20",
                    "4|2|NARROW|1|100|1|30",
                    "5|2|WIDE|1|100|1|40",
                    "6|2|KNEE|1|150|1|50",
                    "7|3|STANDARD|1|100|1|10",
                    "8|3|JUMP|1|75|1|20",
                    "9|3|RAISED_ARMS_JUMP|1|50|1|30",
                    "10|4|STANDARD|1|300|1|10",
                    "11|5|STANDARD|10|1000|1|10",
                    "12|6|STANDARD|10|600|1|10");

            assertThat(singleNumber(connection, "SELECT count(*) FROM catalog_exercises e "
                + "WHERE e.is_active AND NOT EXISTS (SELECT 1 FROM catalog_variations v "
                + "WHERE v.exercise_id = e.id AND v.code = 'STANDARD' AND v.is_active)"))
                .as("every active exercise has an active STANDARD")
                .isZero();
            assertThat(singleNumber(connection, "SELECT count(*) FROM (SELECT e.id "
                + "FROM catalog_exercises e JOIN catalog_variations v ON v.exercise_id = e.id "
                + "AND v.code = 'STANDARD' AND v.is_active WHERE e.is_active "
                + "GROUP BY e.id HAVING count(*) = 1) standard_per_exercise"))
                .as("exactly one active STANDARD per active exercise")
                .isEqualTo(6);
            assertThat(singleNumber(connection, "SELECT count(*) FROM catalog_variations "
                + "WHERE length(btrim(instructions)) = 0 OR length(btrim(description)) = 0"))
                .as("variations have non-empty description and instructions")
                .isZero();
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

    private long singleNumber(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private List<String> queryList(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }
}
