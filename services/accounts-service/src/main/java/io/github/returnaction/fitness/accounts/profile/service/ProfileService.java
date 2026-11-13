package io.github.returnaction.fitness.accounts.profile.service;

import io.github.returnaction.fitness.accounts.profile.dto.ProfileResponse;
import io.github.returnaction.fitness.accounts.profile.entity.UserProfileEntity;
import io.github.returnaction.fitness.accounts.profile.exception.ProfileUnavailableException;
import io.github.returnaction.fitness.accounts.profile.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileService {

    private final ProfileRepository profileRepository;

    public ProfileResponse getOwnProfile(UUID userId) {

        UserProfileEntity userProfileEntity = findProfile(userId);

        return new ProfileResponse(userProfileEntity.getNickname(), userProfileEntity.getFirstName(),
                userProfileEntity.getLastName(), userProfileEntity.getBio(), userProfileEntity.getDateOfBirth(),
                userProfileEntity.getTimezone(), userProfileEntity.getVisibility(), userProfileEntity.getVersion());
    }

    private UserProfileEntity findProfile(UUID userId) {
        try {
            return profileRepository.findById(userId).orElseThrow(ProfileUnavailableException::new);
        } catch (DataAccessResourceFailureException | TransientDataAccessResourceException | QueryTimeoutException
                | CannotCreateTransactionException exception) {

            throw new ProfileUnavailableException(exception);
        }
    }
}
