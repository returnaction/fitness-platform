package io.github.returnaction.fitness.accounts.profile.helpers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.returnaction.fitness.accounts.profile.dto.UpdateProfileRequest;
import io.github.returnaction.fitness.accounts.profile.entity.ProfileVisibility;
import io.github.returnaction.fitness.accounts.profile.exception.ProfileValidationException;
import io.github.returnaction.fitness.accounts.shared.web.ApiErrorResponse.ErrorDetail;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ProfileUpdateValidatorTest {

    private final ProfileUpdateValidator validator = new ProfileUpdateValidator(
            Clock.fixed(Instant.parse("2026-11-13T00:30:00Z"), ZoneOffset.ofHours(-8)));

    private UpdateProfileRequest request(String nickname, String bio) {
        return new UpdateProfileRequest(nickname, null, null, bio, null, "UTC", ProfileVisibility.PRIVATE, 1L);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 30})
    void acceptsNicknameBoundariesAndUnicodeBio(int length) {
        assertThatCode(() -> validator.validate(request("a".repeat(length), "😀".repeat(499) + "\n")))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(request("A_1-z", null))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void requiresNickname(String nickname) {
        var exception = assertThrows(ProfileValidationException.class,
                () -> validator.validate(request(nickname, null)));
        assertThat(exception.getDetails()).containsExactly(new ErrorDetail("nickname", "required"));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 31})
    void rejectsNicknameOutsideLengthBounds(int length) {
        var exception = assertThrows(ProfileValidationException.class,
                () -> validator.validate(request("a".repeat(length), null)));
        assertThat(exception.getDetails()).containsExactly(new ErrorDetail("nickname", "invalid_length"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ник", "a b", "abc\u0000", "abc😀"})
    void rejectsInvalidNicknameCharacters(String nickname) {
        var exception = assertThrows(ProfileValidationException.class,
                () -> validator.validate(request(nickname, null)));
        assertThat(exception.getDetails()).containsExactly(new ErrorDetail("nickname", "invalid_format"));
    }

    @Test
    void rejects501CodePointBio() {
        var exception = assertThrows(ProfileValidationException.class,
                () -> validator.validate(request("Player_1", "😀".repeat(501))));
        assertThat(exception.getDetails()).containsExactly(new ErrorDetail("bio", "invalid_length"));
    }

    @Test
    void collectsErrorsAcrossAllImplementedChecks() {
        var request = new UpdateProfileRequest("!", null, null, "text\u0000", null, "UTC", null, 0L);
        var exception = assertThrows(ProfileValidationException.class, () -> validator.validate(request));
        assertThat(exception.getDetails()).containsExactly(new ErrorDetail("nickname", "invalid_length"),
                new ErrorDetail("nickname", "invalid_format"), new ErrorDetail("bio", "invalid_format"),
                new ErrorDetail("visibility", "required"), new ErrorDetail("version", "out_of_range"));
    }

    private UpdateProfileRequest values(String name, LocalDate date, String timezone, Long version) {
        return new UpdateProfileRequest("Player_1", name, name, null, date, timezone, ProfileVisibility.PUBLIC,
                version);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "---", "’'", "123", "Anna2", "Анна😀", "Анна\tМария", "Анна\nМария", "A\u0000",
            "\u0301A", "A \u0301"})
    void checksNamesWithoutRequiringOptionalValues(String name) {
        var request = values(name, null, "UTC", 1L);
        if (name == null) {
            assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
            return;
        }
        var exception = assertThrows(ProfileValidationException.class, () -> validator.validate(request));
        assertThat(exception.getDetails()).containsExactly(new ErrorDetail("firstName", "invalid_format"),
                new ErrorDetail("lastName", "invalid_format"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Анна", "O'Connor", "О’Коннор", "Jean-Luc", "Анна  Мария", "Jose\u0301", "李", "नमस्ते",
            "𐐀"})
    void acceptsNamesInDifferentScripts(String name) {
        assertThatCode(() -> validator.validate(values(name, null, "UTC", 1L))).doesNotThrowAnyException();
    }

    @Test
    void nameLengthUsesCodePointsIncludingCombiningMarks() {
        for (String name : new String[]{"a".repeat(100), "𐐀".repeat(100), "e\u0301".repeat(50)}) {
            assertThatCode(() -> validator.validate(values(name, null, "UTC", 1L))).doesNotThrowAnyException();
            var exception = assertThrows(ProfileValidationException.class,
                    () -> validator.validate(values(name + "a", null, "UTC", 1L)));
            assertThat(exception.getDetails()).containsExactly(new ErrorDetail("firstName", "invalid_length"),
                    new ErrorDetail("lastName", "invalid_length"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1876-11-13", "2026-11-13", "2000-02-29"})
    void acceptsInclusiveDateBoundsInUtc(String date) {
        assertThatCode(() -> validator.validate(values(null, LocalDate.parse(date), "Pacific/Kiritimati", 1L)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1876-11-12", "2026-11-14"})
    void rejectsDatesOutsideUtcBounds(String date) {
        var exception = assertThrows(ProfileValidationException.class,
                () -> validator.validate(values(null, LocalDate.parse(date), "America/Los_Angeles", 1L)));
        assertThat(exception.getDetails()).containsExactly(new ErrorDetail("dateOfBirth", "out_of_range"));
    }

    @Test
    void leapDayUsesCalendarYearsForLowerBound() {
        var leapValidator = new ProfileUpdateValidator(
                Clock.fixed(Instant.parse("2024-02-29T12:00:00Z"), ZoneOffset.UTC));
        assertThatCode(() -> leapValidator.validate(values(null, LocalDate.of(1874, 2, 28), "UTC", 1L)))
                .doesNotThrowAnyException();
        assertThrows(ProfileValidationException.class,
                () -> leapValidator.validate(values(null, LocalDate.of(1874, 2, 27), "UTC", 1L)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/Moscow", "Asia/Kolkata", "America/New_York"})
    void acceptsNamedTimezones(String zone) {
        assertThatCode(() -> validator.validate(values(null, null, zone, Long.MAX_VALUE))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    void requiresTimezone(String zone) {
        var exception = assertThrows(ProfileValidationException.class,
                () -> validator.validate(values(null, null, zone, 1L)));
        assertThat(exception.getDetails()).containsExactly(new ErrorDetail("timezone", "required"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+03:00", "UTC+03:00", "Mars/Base", "utc", " UTC ", "Europe/Moscow\u0000"})
    void rejectsInvalidTimezone(String zone) {
        var exception = assertThrows(ProfileValidationException.class,
                () -> validator.validate(values(null, null, zone, 1L)));
        assertThat(exception.getDetails()).containsExactly(
                new ErrorDetail("timezone", zone.indexOf(0) >= 0 ? "invalid_format" : "invalid_value"));
    }

    @Test
    void requiresVersionAndRejectsNegativeValues() {
        for (Long version : new Long[]{null, -1L, Long.MIN_VALUE}) {
            var exception = assertThrows(ProfileValidationException.class,
                    () -> validator.validate(values(null, null, "UTC", version)));
            assertThat(exception.getDetails())
                    .containsExactly(new ErrorDetail("version", version == null ? "required" : "out_of_range"));
        }
    }
}
