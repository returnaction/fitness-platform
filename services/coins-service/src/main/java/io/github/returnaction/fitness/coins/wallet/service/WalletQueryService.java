package io.github.returnaction.fitness.coins.wallet.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.returnaction.fitness.coins.wallet.dto.WalletBalanceResponse;
import io.github.returnaction.fitness.coins.wallet.exception.CoinsUnavailableException;
import io.github.returnaction.fitness.coins.wallet.exception.WalletInvariantViolationException;
import io.github.returnaction.fitness.coins.wallet.repository.WalletRepository;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Читает текущий снимок баланса владельца, не создавая и не изменяя кошелёк.
 *
 * <p>Spec 017 T004.5: use case получает userId, читает кошелёк через
 * {@code findById(UUID)} в read-only транзакции, собирает DTO и различает
 * отсутствующий кошелёк и временную ошибку БД. Транзакцию открывает Spring
 * Data, поэтому ошибка соединения попадает в {@code catch} этого метода.</p>
 */
@Service
@RequiredArgsConstructor
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring-managed repository is shared and not exposed")
public class WalletQueryService {

    private final WalletRepository walletRepository;

    public WalletBalanceResponse getOwnBalance(UUID userId) {

        Objects.requireNonNull(userId, "userId");

        try {
            var wallet = walletRepository.findById(userId).orElseThrow(WalletInvariantViolationException::new);

            return new WalletBalanceResponse(wallet.getAvailableBalance(), wallet.getLockedBalance());
        } catch (DataAccessResourceFailureException
                 | TransientDataAccessResourceException
                 | QueryTimeoutException
                 | CannotCreateTransactionException exception) {
            throw new CoinsUnavailableException(exception);
        }
    }
}
