package io.github.returnaction.fitness.challenge.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ChallengeSchemaMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void liquibaseCreatesOnlyMvpTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_type = 'BASE TABLE'
                  AND table_name NOT IN ('databasechangelog', 'databasechangeloglock')
                ORDER BY table_name
                """, String.class);

        assertThat(tables).containsExactly(
                "challenge_idempotency_records",
                "challenge_inbox",
                "challenge_operations",
                "challenge_outbox",
                "challenge_participants",
                "challenge_release_intents",
                "challenge_result_winners",
                "challenge_results",
                "challenge_settlement_status",
                "challenge_stake_set_items",
                "challenge_stake_sets",
                "challenges");
    }

    @Test
    void enforcesPublishChecksAndUniqueness() {
        UUID challengeId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO challenges (id, creator_user_id, status)
                VALUES (?, ?, 'OPEN')
                """, challengeId, creatorId))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                INSERT INTO challenges (id, creator_user_id, status)
                VALUES (?, ?, 'DRAFT')
                """, challengeId, creatorId);

        UUID holdId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO challenge_release_intents (id, challenge_id, hold_id, reason, status)
                VALUES (?, ?, ?, 'JOIN_NOT_CONFIRMED', 'PENDING')
                """, UUID.randomUUID(), challengeId, holdId);
        UUID secondIntentId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO challenge_release_intents (id, challenge_id, hold_id, reason, status)
                VALUES (?, ?, ?, 'CHALLENGE_CANCELLED', 'PENDING')
                """, secondIntentId, challengeId, holdId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void restrictsUnfinishedOperationsToOnePerUser() {
        UUID challengeId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO challenges (id, creator_user_id, status)
                VALUES (?, ?, 'DRAFT')
                """, challengeId, userId);

        jdbc.update("""
                INSERT INTO challenge_operations (id, challenge_id, user_id, kind, status)
                VALUES (?, ?, ?, 'JOIN', 'PENDING')
                """, UUID.randomUUID(), challengeId, userId);
        UUID secondOperationId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO challenge_operations (id, challenge_id, user_id, kind, status)
                VALUES (?, ?, ?, 'JOIN', 'RESERVED')
                """, secondOperationId, challengeId, userId))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                UPDATE challenge_operations
                SET status = 'CONFIRMED', updated_at = CURRENT_TIMESTAMP
                WHERE challenge_id = ? AND user_id = ?
                """, challengeId, userId);
        jdbc.update("""
                INSERT INTO challenge_operations (id, challenge_id, user_id, kind, status)
                VALUES (?, ?, ?, 'JOIN', 'PENDING')
                """, UUID.randomUUID(), challengeId, userId);

        assertThat(indexDefinition("ux_challenge_operations_active")).contains("WHERE", "status");
    }

    private String indexDefinition(String name) {
        return jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = ?
                """, String.class, name);
    }
}
