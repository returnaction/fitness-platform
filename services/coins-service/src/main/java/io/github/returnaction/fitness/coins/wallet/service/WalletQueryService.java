package io.github.returnaction.fitness.coins.wallet.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.returnaction.fitness.coins.wallet.dto.WalletBalanceResponse;
import io.github.returnaction.fitness.coins.wallet.exception.CoinsUnavailableException;
import io.github.returnaction.fitness.coins.wallet.exception.WalletInvariantViolationException;
import io.github.returnaction.fitness.coins.wallet.repository.WalletRepository;
import java.util.Objects;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.UUID;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;
import org.hibernate.TransactionException;
import org.hibernate.exception.JDBCConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
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
            if (hasConnectionFailure(exception)) {
                throw new CoinsUnavailableException(exception);
            }
            throw exception;
        }
    }

    private boolean hasConnectionFailure(Throwable exception) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof JDBCConnectionException) {
                return true;
            }
            if (cause instanceof TransactionException && cause.getCause() instanceof SQLException sql) {
                String state = sql.getSQLState();
                // Hikari's closed-connection sentinel has no SQLState. Hibernate can
                // replace the original connection error with this rollback failure.
                if ((state != null && state.startsWith("08"))
                        || (state == null && "Connection is closed".equals(sql.getMessage()))) {
                    return true;
                }
            }
        }
        return false;
    }
}
