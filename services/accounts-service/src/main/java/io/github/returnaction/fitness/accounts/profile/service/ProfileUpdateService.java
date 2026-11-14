package io.github.returnaction.fitness.accounts.profile.service;

import io.github.returnaction.fitness.accounts.profile.dto.ProfileResponse;
import io.github.returnaction.fitness.accounts.profile.dto.UpdateProfileRequest;
import io.github.returnaction.fitness.accounts.profile.exception.ProfileUnavailableException;
import io.github.returnaction.fitness.accounts.profile.exception.ProfileVersionConflictException;
import io.github.returnaction.fitness.accounts.profile.repository.ProfileRepository;
import io.github.returnaction.fitness.accounts.profile.repository.projection.UpdateProfileProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileUpdateService {

    private final ProfileRepository profileRepository;

    @Transactional(timeout = 2)
    public ProfileResponse update(UUID userId, UpdateProfileRequest request) {

        Optional<UpdateProfileProjection> result = profileRepository.updateProfile(
            userId,
            request.version(),
            request.nickname(),
            request.firstName(),
            request.lastName(),
            request.bio(),
            request.dateOfBirth(),
            request.timezone(),
            request.visibility().name()
        );

        if (result.isEmpty()) {
            if (!profileRepository.existsById(userId)) {
                throw new ProfileUnavailableException();
            }

            throw new ProfileVersionConflictException();
        }

        UpdateProfileProjection profile = result.get();

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

}
