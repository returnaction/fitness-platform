package io.github.returnaction.fitness.accounts.profile.helpers;

import io.github.returnaction.fitness.accounts.profile.dto.UpdateProfileRequest;
import org.springframework.stereotype.Component;

@Component
public class ProfileUpdateNormalizer {

    public UpdateProfileRequest normalize(UpdateProfileRequest request) {

        return new UpdateProfileRequest(
            strip(request.nickname()),
            normalizeOptional(request.firstName()),
            normalizeOptional(request.lastName()),
            normalizeOptional(request.bio()),
            request.dateOfBirth(),
            request.timezone(),
            request.visibility(),
            request.version());
    }

    private String normalizeOptional(String value) {
        String normalized = strip(value);

        if (normalized == null || normalized.isEmpty())
            return null;

        return normalized;
    }

    private String strip(String value) {
        if (value == null)
            return null;

        return value.strip();
    }
}
