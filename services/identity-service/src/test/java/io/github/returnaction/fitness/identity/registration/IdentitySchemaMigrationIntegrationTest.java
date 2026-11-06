package io.github.returnaction.fitness.identity.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
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
class IdentitySchemaMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void createsIdentitySchemaAndProtectsCanonicalEmailUniqueness() throws Exception {
        applyMigrations();

        try (Connection connection =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(tableNames(connection))
                    .contains(
                            "users",
                            "user_credentials",
                            "roles",
                            "user_roles",
                            "registration_operations",
                            "auth_sessions",
                            "refresh_tokens");
            assertThat(roleCount(connection)).isEqualTo(4);

            insertUser(connection, UUID.randomUUID(), "anna@example.com");
            assertThatThrownBy(() -> insertUser(connection, UUID.randomUUID(), "anna@example.com"))
                    .isInstanceOf(SQLException.class);
        }
    }

    private void applyMigrations() throws Exception {
        try (Connection connection =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private Set<String> tableNames(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT table_name FROM information_schema.tables " + "WHERE table_schema = 'public'");
                ResultSet resultSet = statement.executeQuery()) {
            Set<String> result = new java.util.HashSet<>();
            while (resultSet.next()) {
                result.add(resultSet.getString("table_name"));
            }
            return result;
        }
    }

    private int roleCount(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM roles");
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private void insertUser(Connection connection, UUID id, String email) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users (id, email_canonical, email_version, status) VALUES (?, ?, 1, 'PENDING')")) {
            statement.setObject(1, id);
            statement.setString(2, email);
            statement.executeUpdate();
        }
    }
}
