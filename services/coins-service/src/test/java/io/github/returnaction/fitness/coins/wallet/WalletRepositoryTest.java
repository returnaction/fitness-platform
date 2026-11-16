package io.github.returnaction.fitness.coins.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.returnaction.fitness.coins.wallet.grpc.SecureGrpcServer;
import io.github.returnaction.fitness.coins.wallet.repository.WalletRepository;
import io.github.returnaction.fitness.coins.wallet.service.WalletQueryService;
import io.github.returnaction.fitness.coins.wallet.exception.CoinsUnavailableException;
import java.sql.DriverManager;
import javax.sql.DataSource;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Проверяет чтение кошелька через существующий {@code findById(UUID)}.
 *
 * <p>Spec 017 T004.4/T004.10: подтверждает, что кошелёк владельца находится,
 * а запись другого пользователя не возвращается; отдельный запрос репозитория
 * не добавляется.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class WalletRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @MockitoBean
    private SecureGrpcServer managedGrpcServer;

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private WalletQueryService queryService;

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);
    }

    @Test
    void reportsTerminatedPooledConnectionAsUnavailableAndRecovers() throws Exception {
        UUID owner = UUID.randomUUID();
        transactions.executeWithoutResult(status -> wallets.insertIfAbsent(owner));
        try (var admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var command = admin.createStatement()) {
            int backend;
            try (var connection = dataSource.getConnection();
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                result.next();
                backend = result.getInt(1);
            }
            command.execute("SELECT pg_terminate_backend(" + backend + ")");

            Throwable failure = catchThrowable(() -> queryService.getOwnBalance(owner));
            assertThat(failure).isInstanceOf(CoinsUnavailableException.class);
        }
        assertThat(queryService.getOwnBalance(owner).availableBalance()).isZero();
    }

    @Test
    void findsExistingWalletByUserId() {
        UUID owner = UUID.randomUUID();

        transactions.executeWithoutResult(status -> wallets.insertIfAbsent(owner));

        var wallet = transactions.execute(status -> wallets.findById(owner)).orElseThrow();

        assertThat(wallet.getUserId()).isEqualTo(owner);
        assertThat(wallet.getAvailableBalance()).isZero();
        assertThat(wallet.getLockedBalance()).isZero();
    }

    @Test
    void doesNotReturnWalletOfAnotherUser() {
        UUID owner = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();

        transactions.executeWithoutResult(status -> wallets.insertIfAbsent(owner));

        assertThat(wallets.findById(owner)).isPresent();
        assertThat(wallets.findById(otherUser)).isEmpty();
    }
}
