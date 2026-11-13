package io.github.returnaction.fitness.accounts.profile.helpers;

import io.github.returnaction.fitness.accounts.profile.dto.UpdateProfileRequest;
import io.github.returnaction.fitness.accounts.profile.exception.ProfileValidationException;
import io.github.returnaction.fitness.accounts.shared.web.ApiErrorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class ProfileUpdateValidator {

    private static final Pattern NICKNAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern NAME_PATTERN = Pattern.compile("(?:\\p{L}\\p{M}*|[ '’-])+");
    private static final Set<String> TIMEZONES = ZoneId.getAvailableZoneIds();

    private final Clock clock;

    public void validate(UpdateProfileRequest request) {

        List<ApiErrorResponse.ErrorDetail> errors = new ArrayList<>();

        validateNickname(request.nickname(), errors);
        validateName("firstName", request.firstName(), errors);
        validateName("lastName", request.lastName(), errors);
        validateBio(request.bio(), errors);
        validateDateOfBirth(request.dateOfBirth(), errors);
        validateTimezone(request.timezone(), errors);

        if (request.visibility() == null) {
            errors.add(new ApiErrorResponse.ErrorDetail("visibility", "required"));
        }

        if (request.version() == null) {
            errors.add(new ApiErrorResponse.ErrorDetail("version", "required"));
        } else if (request.version() <= 0) {
            errors.add(new ApiErrorResponse.ErrorDetail("version", "out_of_range"));
        }

        if (!errors.isEmpty()) {
            throw new ProfileValidationException(errors);
        }

    }

    private void validateNickname(String nickname, List<ApiErrorResponse.ErrorDetail> errors) {
        if (nickname == null || nickname.isEmpty()) {
            errors.add(new ApiErrorResponse.ErrorDetail("nickname", "required"));
            return;
        }

        int length = nickname.codePointCount(0, nickname.length());

        if (length < 3 || length > 30) {
            errors.add(new ApiErrorResponse.ErrorDetail("nickname", "invalid_length"));
        }

        if (!NICKNAME_PATTERN.matcher(nickname).matches()) {
            errors.add(new ApiErrorResponse.ErrorDetail("nickname", "invalid_format"));
        }
    }

    private void validateName(String field, String value, List<ApiErrorResponse.ErrorDetail> errors) {
        if (value == null) {
            return;
        }

        int length = value.codePointCount(0, value.length());

        if (length > 100) {
            errors.add(new ApiErrorResponse.ErrorDetail(field, "invalid_length"));
        }

        boolean containsLetter = value.codePoints().anyMatch(Character::isLetter);
        boolean allowedCharacters = NAME_PATTERN.matcher(value).matches();

        if (!containsLetter || !allowedCharacters) {
            errors.add(new ApiErrorResponse.ErrorDetail(field, "invalid_format"));
        }
    }

    private void validateBio(String bio, List<ApiErrorResponse.ErrorDetail> errors) {
        if (bio == null) {
            return;
        }

        int length = bio.codePointCount(0, bio.length());

        if (length > 500) {
            errors.add(new ApiErrorResponse.ErrorDetail("bio", "invalid_length"));
        }

        if (bio.indexOf(0) >= 0) {
            errors.add(new ApiErrorResponse.ErrorDetail("bio", "invalid_format"));
        }
    }

    private void validateDateOfBirth(LocalDate dateOfBirth, List<ApiErrorResponse.ErrorDetail> errors) {
        if (dateOfBirth == null) {
            return;
        }

        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate earliestDate = today.minusYears(150);

        if (dateOfBirth.isBefore(earliestDate) || dateOfBirth.isAfter(today)) {
            errors.add(new ApiErrorResponse.ErrorDetail("dateOfBirth", "out_of_range"));
        }
    }

    private void validateTimezone(String timezone, List<ApiErrorResponse.ErrorDetail> errors) {
        if (timezone == null || timezone.isBlank()) {
            errors.add(new ApiErrorResponse.ErrorDetail("timezone", "required"));
            return;
        }

        if (timezone.indexOf(0) >= 0) {
            errors.add(new ApiErrorResponse.ErrorDetail("timezone", "invalid_format"));
            return;
        }

        if (!TIMEZONES.contains(timezone)) {
            errors.add(new ApiErrorResponse.ErrorDetail("timezone", "invalid_value"));
        }
    }
}
