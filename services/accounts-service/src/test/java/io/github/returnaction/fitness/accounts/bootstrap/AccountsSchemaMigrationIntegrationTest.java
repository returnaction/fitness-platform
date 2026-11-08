package io.github.returnaction.fitness.accounts.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

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
class AccountsSchemaMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void appliesProfileMigration() throws Exception {
        applyMigrations();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            assertThat(changeSetCount(connection)).isEqualTo(1);
            assertThat(applicationTableCount(connection)).isEqualTo(1);
        }
    }

    private void applyMigrations() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private int changeSetCount(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM databasechangelog");
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private int applicationTableCount(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name NOT LIKE 'databasechangelog%'");
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }
}
