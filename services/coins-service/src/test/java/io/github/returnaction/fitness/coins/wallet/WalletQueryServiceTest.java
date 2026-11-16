package io.github.returnaction.fitness.coins.wallet;

import io.github.returnaction.fitness.coins.wallet.exception.CoinsUnavailableException;
import io.github.returnaction.fitness.coins.wallet.exception.WalletInvariantViolationException;
import io.github.returnaction.fitness.coins.wallet.repository.WalletRepository;
import io.github.returnaction.fitness.coins.wallet.service.WalletQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.hibernate.exception.JDBCConnectionException;
import org.hibernate.TransactionException;
import java.sql.SQLException;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class WalletQueryServiceTest {

    private final WalletRepository repository = mock(WalletRepository.class);
    private final WalletQueryService service = new WalletQueryService(repository);
    private final UUID userId = UUID.randomUUID();

    @ParameterizedTest
    @MethodSource("temporaryFailures")
    void wrapsTemporaryRepositoryFailureAndPreservesCause(RuntimeException failure) {
        when(repository.findById(userId)).thenThrow(failure);

        assertThatThrownBy(() -> service.getOwnBalance(userId))
            .isInstanceOf(CoinsUnavailableException.class)
            .hasCauseReference(failure);
        verify(repository).findById(userId);
        verifyNoMoreInteractions(repository);
    }

    static Stream<RuntimeException> temporaryFailures() {
        return Stream.of(
            new DataAccessResourceFailureException("connection lost"),
            new TransientDataAccessResourceException("resource unavailable"),
            new QueryTimeoutException("query timed out"),
            new CannotCreateTransactionException("cannot open transaction")
        );
    }

    @ParameterizedTest
    @MethodSource("unexpectedFailures")
    void doesNotDisguiseUnexpectedFailureAsTemporary(RuntimeException failure) {
        when(repository.findById(userId)).thenThrow(failure);

        assertThatThrownBy(() -> service.getOwnBalance(userId)).isSameAs(failure);
    }

    static Stream<RuntimeException> unexpectedFailures() {
        return Stream.of(
            new IllegalStateException("unexpected failure"),
            new DataIntegrityViolationException("invalid data")
        );
    }

    @Test
    void wrapsJpaFailureCausedByBrokenConnection() {
        var connectionFailure = new JDBCConnectionException(
            "connection lost",
            new SQLException("connection reset", "08006")
        );
        var failure = new JpaSystemException(new IllegalStateException(connectionFailure));
        when(repository.findById(userId)).thenThrow(failure);

        assertThatThrownBy(() -> service.getOwnBalance(userId))
            .isInstanceOf(CoinsUnavailableException.class)
            .hasCauseReference(failure);
    }

    @Test
    void wrapsRollbackFailureOnConnectionClosedByPool() {
        var failure = new JpaSystemException(new TransactionException(
            "Unable to rollback against JDBC Connection",
            new SQLException("Connection is closed")
        ));
        when(repository.findById(userId)).thenThrow(failure);

        assertThatThrownBy(() -> service.getOwnBalance(userId))
            .isInstanceOf(CoinsUnavailableException.class)
            .hasCauseReference(failure);
    }

    @Test
    void preservesTransactionFailureUnrelatedToConnection() {
        var failure = new JpaSystemException(new TransactionException(
            "transaction failed",
            new SQLException("constraint violation", "23514")
        ));
        when(repository.findById(userId)).thenThrow(failure);

        assertThatThrownBy(() -> service.getOwnBalance(userId)).isSameAs(failure);
    }

    @Test
    void preservesJpaFailureUnrelatedToConnection() {
        var failure = new JpaSystemException(new IllegalStateException("mapping failure"));
        when(repository.findById(userId)).thenThrow(failure);

        assertThatThrownBy(() -> service.getOwnBalance(userId)).isSameAs(failure);
    }

    @Test
    void missingWalletIsAnInvariantViolationWithoutCreatingWallet() {
        when(repository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOwnBalance(userId))
            .isInstanceOf(WalletInvariantViolationException.class);
        verify(repository).findById(userId);
        verifyNoMoreInteractions(repository);
    }
}
