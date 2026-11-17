package io.github.returnaction.fitness.coins.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.coins.wallet.grpc.SecureGrpcServer;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class CoinsSchemaMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean
    private SecureGrpcServer grpcServer;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsStakeAndSettlementTablesWithoutChangingWallet() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_type = 'BASE TABLE'
                  AND table_name NOT IN ('databasechangelog', 'databasechangeloglock')
                ORDER BY table_name
                """, String.class);

        assertThat(tables).containsExactly(
                "coin_holds",
                "coin_inbox",
                "coin_ledger_entries",
                "coin_outbox",
                "coin_sealed_stakes",
                "coin_settlement_allocations",
                "coin_settlement_hold_moves",
                "coin_settlement_winners",
                "coin_settlements",
                "coin_stake_operations",
                "coin_stake_seal_batches",
                "coin_stake_seal_items",
                "coin_stake_seal_uploads",
                "coin_stake_sets",
                "coin_wallets");

        List<String> walletColumns = jdbc.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'coin_wallets'
                ORDER BY column_name
                """, String.class);
        assertThat(walletColumns).containsExactly(
                "available_balance", "created_at", "locked_balance", "updated_at", "user_id");
    }

    @Test
    void enforcesStakeConstraints() {
        UUID userId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        jdbc.update("INSERT INTO coin_wallets (user_id) VALUES (?)", userId);

        jdbc.update("""
                INSERT INTO coin_holds (id, user_id, challenge_id, join_operation_id, amount, status)
                VALUES (?, ?, ?, ?, 10, 'ACTIVE')
                """, UUID.randomUUID(), userId, challengeId, UUID.randomUUID());
        UUID duplicateHoldId = UUID.randomUUID();
        UUID duplicateOperationId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO coin_holds (id, user_id, challenge_id, join_operation_id, amount, status)
                VALUES (?, ?, ?, ?, 10, 'ACTIVE')
                """, duplicateHoldId, userId, challengeId, duplicateOperationId))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID stakeSetId = UUID.randomUUID();
        byte[] digest = new byte[32];
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO coin_stake_sets (challenge_id, state, digest)
                VALUES (?, 'OPEN', ?)
                """, stakeSetId, digest))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
