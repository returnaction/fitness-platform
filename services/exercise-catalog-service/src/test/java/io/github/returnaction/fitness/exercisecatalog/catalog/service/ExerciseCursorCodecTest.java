package io.github.returnaction.fitness.exercisecatalog.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.exercisecatalog.catalog.exception.InvalidCursorException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ExerciseCursorCodecTest {

    private final ExerciseCursorCodec codec = new ExerciseCursorCodec(new ObjectMapper());

    @Test
    void roundTripsCategoryAndPosition() {
        String token = codec.encode(2, 40, 15);

        ExerciseCursor cursor = codec.decode(token, 2);

        assertThat(cursor).isEqualTo(new ExerciseCursor(2, 40, 15));
    }

    @Test
    void roundTripsWithoutCategoryFilter() {
        String token = codec.encode(null, 10, 1);

        assertThat(codec.decode(token, null)).isEqualTo(new ExerciseCursor(null, 10, 1));
    }

    @Test
    void treatsBlankTokenAsNoCursor() {
        assertThat(codec.decode(null, null)).isNull();
        assertThat(codec.decode("   ", null)).isNull();
    }

    @Test
    void rejectsMalformedBase64AndJson() {
        assertThatThrownBy(() -> codec.decode("not-base64!", null))
            .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> codec.decode(base64("hello"), null))
            .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void rejectsUnknownVersionAndMissingFields() {
        assertThatThrownBy(() -> codec.decode(base64("{\"v\":2,\"c\":null,\"s\":10,\"i\":1}"), null))
            .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> codec.decode(base64("{\"v\":1,\"c\":null}"), null))
            .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void rejectsCursorBoundToAnotherCategory() {
        String token = codec.encode(1, 10, 1);

        assertThatThrownBy(() -> codec.decode(token, 2))
            .isInstanceOf(InvalidCursorException.class);
    }

    private String base64(String json) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
