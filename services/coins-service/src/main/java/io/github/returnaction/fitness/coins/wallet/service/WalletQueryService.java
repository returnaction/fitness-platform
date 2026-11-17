package io.github.returnaction.fitness.coins.wallet.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.returnaction.fitness.coins.shared.persistence.ConnectionFailures;
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
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;


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
        } catch (JpaSystemException exception) {
            if (ConnectionFailures.causedByConnectionFailure(exception)) {
                throw new CoinsUnavailableException(exception);
            }
            throw exception;
        }
    }
}
