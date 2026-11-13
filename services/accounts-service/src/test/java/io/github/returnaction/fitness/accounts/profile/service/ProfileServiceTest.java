package io.github.returnaction.fitness.accounts.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import io.github.returnaction.fitness.accounts.profile.entity.UserProfileEntity;
import io.github.returnaction.fitness.accounts.profile.exception.ProfileUnavailableException;
import io.github.returnaction.fitness.accounts.profile.repository.ProfileRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.transaction.CannotCreateTransactionException;

class ProfileServiceTest {

    private final ProfileRepository repository = mock(ProfileRepository.class);
    private final ProfileService service = new ProfileService(repository);
    private final UUID userId = UUID.randomUUID();

    @Test
    void readsOnlyRequestedOwnerWithoutWrites() {
        when(repository.findById(userId))
                .thenReturn(Optional.of(new UserProfileEntity(userId, UUID.randomUUID(), "Player_1")));
        var response = service.getOwnProfile(userId);
        assertThat(response.nickname()).isEqualTo("Player_1");
        assertThat(response.version()).isEqualTo(1L);
        verify(repository).findById(userId);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void missingProfileIsUnavailableWithoutCreatingIt() {
        when(repository.findById(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getOwnProfile(userId)).isInstanceOf(ProfileUnavailableException.class)
                .hasNoCause();
        verify(repository).findById(userId);
        verifyNoMoreInteractions(repository);
    }

    static Stream<RuntimeException> unavailableFailures() {
        return Stream.of(new DataAccessResourceFailureException("private-database-detail"),
                new TransientDataAccessResourceException("private-database-detail"),
                new QueryTimeoutException("private-database-detail"),
                new CannotCreateTransactionException("private-database-detail"));
    }

    @ParameterizedTest
    @MethodSource("unavailableFailures")
    void preservesCauseOfUnavailableReadWithoutRetryOrWrite(RuntimeException cause) {
        when(repository.findById(userId)).thenThrow(cause);
        assertThatThrownBy(() -> service.getOwnProfile(userId)).isInstanceOf(ProfileUnavailableException.class)
                .hasCauseReference(cause);
        verify(repository).findById(userId);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void programmingAndSqlErrorsAreNotDisguisedAsTemporaryUnavailability() {
        var cause = new InvalidDataAccessResourceUsageException("bad SQL");
        when(repository.findById(userId)).thenThrow(cause);
        assertThatThrownBy(() -> service.getOwnProfile(userId)).isSameAs(cause);
    }
}
