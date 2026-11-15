package io.github.returnaction.fitness.coins.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.returnaction.fitness.coins.wallet.grpc.SecureGrpcServer;
import io.github.returnaction.fitness.coins.wallet.repository.WalletRepository;
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

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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
