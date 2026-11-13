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
import io.github.returnaction.fitness.accounts.profile.dto.UpdateProfileRequest;
import io.github.returnaction.fitness.accounts.profile.dto.ProfileResponse;
import io.github.returnaction.fitness.accounts.profile.entity.ProfileVisibility;
import io.github.returnaction.fitness.accounts.profile.exception.ProfileValidationException;
import io.github.returnaction.fitness.accounts.profile.helpers.ProfileUpdateNormalizer;
import io.github.returnaction.fitness.accounts.profile.helpers.ProfileUpdateValidator;
import java.time.Clock;
import java.sql.SQLException;
import io.github.returnaction.fitness.accounts.profile.exception.NicknameAlreadyTakenException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import static org.mockito.Mockito.verifyNoInteractions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.transaction.CannotCreateTransactionException;

class ProfileServiceTest {

    private final ProfileRepository repository = mock(ProfileRepository.class);
    private final ProfileUpdateService updater = mock(ProfileUpdateService.class);
    private final ProfileService service = new ProfileService(repository, new ProfileUpdateNormalizer(),
        new ProfileUpdateValidator(Clock.systemUTC()), updater);
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

    @Test
    void normalizesBeforeCallingTransactionalUpdater() {
        var input = new UpdateProfileRequest("  Player_1  ", "  Анна  ", "   ", null, null, "UTC", ProfileVisibility.PRIVATE, 1L);
        var normalized = new UpdateProfileRequest("Player_1", "Анна", null, null, null, "UTC", ProfileVisibility.PRIVATE, 1L);
        var expected = new ProfileResponse("Player_1", "Анна", null, null, null, "UTC", ProfileVisibility.PRIVATE, 2L);
        when(updater.update(userId, normalized)).thenReturn(expected);
        assertThat(service.updateOwnProfile(userId, input)).isEqualTo(expected);
        verify(updater).update(userId, normalized);
        verifyNoInteractions(repository);
    }

    @Test
    void invalidInputNeverReachesDatabaseOrTransactionalUpdater() {
        var input = new UpdateProfileRequest("!", null, null, null, null, "UTC", ProfileVisibility.PRIVATE, 1L);
        assertThatThrownBy(() -> service.updateOwnProfile(userId, input)).isInstanceOf(ProfileValidationException.class);
        verifyNoInteractions(repository, updater);
    }

    @ParameterizedTest
    @MethodSource("unavailableFailures")
    void catchesTemporaryFailuresOutsideTransactionalUpdater(RuntimeException cause) {
        var input = new UpdateProfileRequest("Player_1", null, null, null, null, "UTC", ProfileVisibility.PRIVATE, 1L);
        when(updater.update(userId, input)).thenThrow(cause);
        assertThatThrownBy(() -> service.updateOwnProfile(userId, input))
            .isInstanceOf(ProfileUnavailableException.class).hasCauseReference(cause);
    }

    @Test
    void translatesOnlyNicknameUniqueViolationAndPreservesCause() {
        var input = new UpdateProfileRequest("Player_1", null, null, null, null, "UTC", ProfileVisibility.PRIVATE, 1L);
        var violation = new ConstraintViolationException("private-details", new SQLException("private-sql", "23505"),
            "user_profiles_nickname_unique");
        var cause = new DataIntegrityViolationException("outer", new RuntimeException("wrapper", violation));
        when(updater.update(userId, input)).thenThrow(cause);
        assertThatThrownBy(() -> service.updateOwnProfile(userId, input))
            .isInstanceOf(NicknameAlreadyTakenException.class).hasCauseReference(cause);
        verify(updater).update(userId, input);
        verifyNoMoreInteractions(updater);
        verifyNoInteractions(repository);
    }

    static Stream<DataIntegrityViolationException> otherIntegrityFailures() {
        return Stream.of(
            new DataIntegrityViolationException("no nested constraint"),
            new DataIntegrityViolationException("other unique", new ConstraintViolationException("detail",
                new SQLException("detail", "23505"), "user_profiles_public_profile_id_key")),
            new DataIntegrityViolationException("unknown index", new ConstraintViolationException("detail",
                new SQLException("detail", "23505"), null)),
            new DataIntegrityViolationException("wrong state", new ConstraintViolationException("detail",
                new SQLException("detail", "23514"), "user_profiles_nickname_unique")),
            new DataIntegrityViolationException("unknown state", new ConstraintViolationException("detail",
                new SQLException("detail"), "user_profiles_nickname_unique"))
        );
    }

    @ParameterizedTest
    @MethodSource("otherIntegrityFailures")
    void doesNotDisguiseOtherIntegrityFailuresAsOccupiedNickname(DataIntegrityViolationException cause) {
        var input = new UpdateProfileRequest("Player_1", null, null, null, null, "UTC", ProfileVisibility.PRIVATE, 1L);
        when(updater.update(userId, input)).thenThrow(cause);
        assertThatThrownBy(() -> service.updateOwnProfile(userId, input)).isSameAs(cause);
        verify(updater).update(userId, input);
        verifyNoMoreInteractions(updater);
        verifyNoInteractions(repository);
    }
}
