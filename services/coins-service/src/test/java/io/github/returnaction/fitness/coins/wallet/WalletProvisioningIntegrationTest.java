package io.github.returnaction.fitness.coins.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class WalletProvisioningIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private WalletProvisioningService service;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void concurrentRequestsCreateOneWalletAndRepeatsPreserveBalance() throws Exception {
        UUID userId = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.ensureWallet(userId));
            var second = executor.submit(() -> service.ensureWallet(userId));
            first.get();
            second.get();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM coin_wallets WHERE user_id = :id")
                        .param("id", userId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT available_balance + locked_balance FROM coin_wallets WHERE user_id = :id")
                        .param("id", userId)
                        .query(Long.class)
                        .single())
                .isZero();
        jdbc.sql("UPDATE coin_wallets SET available_balance = 25, locked_balance = 4 WHERE user_id = :id")
                .param("id", userId)
                .update();
        service.ensureWallet(userId);
        assertThat(jdbc.sql("SELECT available_balance + locked_balance FROM coin_wallets WHERE user_id = :id")
                        .param("id", userId)
                        .query(Long.class)
                        .single())
                .isEqualTo(29);
        assertThatThrownBy(() -> jdbc.sql("UPDATE coin_wallets SET available_balance = -1 WHERE user_id = :id")
                        .param("id", userId)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
