package io.github.returnaction.fitness.coins.wallet.service;

import java.util.Objects;
import java.util.UUID;
import io.github.returnaction.fitness.coins.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Создаёт кошелёк только при отсутствии и не меняет существующий баланс. */
@Service
@RequiredArgsConstructor
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring-managed repository is shared and not exposed")
public class WalletCreationService {

    private final WalletRepository walletRepository;

    @Transactional
    public void createWalletIfAbsent(UUID userId) {

        Objects.requireNonNull(userId, "userId");
        walletRepository.insertIfAbsent(userId);
    }
}
