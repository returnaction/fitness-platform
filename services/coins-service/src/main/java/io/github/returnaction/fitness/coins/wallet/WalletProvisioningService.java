package io.github.returnaction.fitness.coins.wallet;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Создаёт кошелёк только при отсутствии и не меняет существующий баланс. */
@Service
public class WalletProvisioningService {
    private final JdbcClient jdbc;

    public WalletProvisioningService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void ensureWallet(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        jdbc.sql("INSERT INTO coin_wallets (user_id) VALUES (:userId) ON CONFLICT (user_id) DO NOTHING")
                .param("userId", userId)
                .update();
    }
}
