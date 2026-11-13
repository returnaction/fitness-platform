package io.github.returnaction.fitness.accounts.profile.helpers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.returnaction.fitness.accounts.profile.dto.UpdateProfileRequest;
import io.github.returnaction.fitness.accounts.profile.entity.ProfileVisibility;
import io.github.returnaction.fitness.accounts.profile.exception.ProfileValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ProfileUpdateNormalizerTest {

    private final ProfileUpdateNormalizer normalizer = new ProfileUpdateNormalizer();
    private final ProfileUpdateValidator validator = new ProfileUpdateValidator(
            Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void stripsEdgesPreservesInteriorAndLeavesOtherFieldsUnchanged() {
        var original = new UpdateProfileRequest("\u2003 Player_1 \t", "  Анна  Мария  ", " О’Коннор ",
                "\n текст  😀\nстрока \t", LocalDate.of(2000, 2, 29), " UTC ", ProfileVisibility.PUBLIC, 7L);
        var result = normalizer.normalize(original);
        assertThat(result).isEqualTo(new UpdateProfileRequest("Player_1", "Анна  Мария", "О’Коннор",
                "текст  😀\nстрока", original.dateOfBirth(), " UTC ", ProfileVisibility.PUBLIC, 7L));
        assertThat(original.firstName()).isEqualTo("  Анна  Мария  ");
        assertThat(normalizer.normalize(result)).isEqualTo(result);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\n\t\u2003"})
    void optionalEmptyTextBecomesNullButNicknameStillFailsValidation(String text) {
        var result = normalizer.normalize(
                new UpdateProfileRequest(text, text, text, text, null, "UTC", ProfileVisibility.PRIVATE, 1L));
        assertThat(result.firstName()).isNull();
        assertThat(result.lastName()).isNull();
        assertThat(result.bio()).isNull();
        assertThrows(ProfileValidationException.class, () -> validator.validate(result));
    }

    @Test
    void validatesLengthAfterNormalization() {
        var original = new UpdateProfileRequest("  " + "a".repeat(30) + "  ", "  " + "𐐀".repeat(100) + "  ", "  ",
                "  " + "😀".repeat(500) + "  ", null, "UTC", ProfileVisibility.PRIVATE, 1L);
        assertThrows(ProfileValidationException.class, () -> validator.validate(original));
        assertThatCode(() -> validator.validate(normalizer.normalize(original))).doesNotThrowAnyException();
    }

    @Test
    void doesNotStripNullCharacterOrNonBreakingSpaceIntoValidInput() {
        for (String text : new String[]{"\u0000", "\u00a0"}) {
            var result = normalizer.normalize(
                    new UpdateProfileRequest(text, text, text, text, null, "UTC", ProfileVisibility.PRIVATE, 1L));
            assertThat(result.nickname()).isEqualTo(text);
            assertThat(result.firstName()).isEqualTo(text);
            assertThat(result.bio()).isEqualTo(text);
            assertThrows(ProfileValidationException.class, () -> validator.validate(result));
        }
    }
}
