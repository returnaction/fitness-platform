package io.github.returnaction.fitness.accounts.profile.service;

import io.github.returnaction.fitness.accounts.profile.dto.ProfileResponse;
import io.github.returnaction.fitness.accounts.profile.dto.UpdateProfileRequest;
import io.github.returnaction.fitness.accounts.profile.entity.UserProfileEntity;
import io.github.returnaction.fitness.accounts.profile.exception.NicknameAlreadyTakenException;
import io.github.returnaction.fitness.accounts.profile.exception.ProfileUnavailableException;
import io.github.returnaction.fitness.accounts.profile.helpers.ProfileUpdateNormalizer;
import io.github.returnaction.fitness.accounts.profile.helpers.ProfileUpdateValidator;
import io.github.returnaction.fitness.accounts.profile.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileService {

    private final ProfileRepository profileRepository;
    private final ProfileUpdateNormalizer profileUpdateNormalizer;
    private final ProfileUpdateValidator profileUpdateValidator;
    private final ProfileUpdateService profileUpdateService;

    /// Получить свой профиль
    public ProfileResponse getOwnProfile(UUID userId) {

        UserProfileEntity profile = findProfile(userId);

        return new ProfileResponse(
            profile.getNickname(),
            profile.getFirstName(),
            profile.getLastName(),
            profile.getBio(),
            profile.getDateOfBirth(),
            profile.getTimezone(),
            profile.getVisibility(),
            profile.getVersion()
        );
    }

    /// Обновить свой профиль
    public ProfileResponse updateOwnProfile(UUID userId, UpdateProfileRequest request) {

        UpdateProfileRequest normalized = profileUpdateNormalizer.normalize(request);
        profileUpdateValidator.validate(normalized);

        try {
            return profileUpdateService.update(userId, normalized);
        } catch (DataIntegrityViolationException exception) {
            if (isNicknameConflict(exception)) {
                throw new NicknameAlreadyTakenException(exception);
            }

            throw exception;

        } catch (DataAccessResourceFailureException
                 | TransientDataAccessResourceException
                 | QueryTimeoutException
                 | CannotCreateTransactionException exception) {
            throw new ProfileUnavailableException(exception);
        }
    }

    private UserProfileEntity findProfile(UUID userId) {
        try {
            return profileRepository.findById(userId).orElseThrow(ProfileUnavailableException::new);
        } catch (DataAccessResourceFailureException
                 | TransientDataAccessResourceException
                 | QueryTimeoutException
                 | CannotCreateTransactionException exception) {
            throw new ProfileUnavailableException(exception);
        }
    }

    private boolean isNicknameConflict(Throwable exception) {
        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation) {
                return "23505".equals(violation.getSQLState())
                    && "user_profiles_nickname_unique".equals(violation.getConstraintName());
            }

            cause = cause.getCause();
        }

        return false;
    }
}
