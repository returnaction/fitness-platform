package io.github.returnaction.fitness.exercisecatalog.catalog.pagination;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Непрозрачный курсор списка упражнений (решение D002, предлагает junior и
 * ожидает утверждения владельцем): Base64URL от "v1:{categoryId}:{sortOrder}:{id}".
 * Курсор привязан к фильтру category_id; порядок ключей (sort_order, id).
 * Неверный формат, мусор или чужой фильтр — 400 invalid_cursor.
 */
@Component
public class ExerciseCursorCodec {

    private static final String VERSION = "v1";
    private static final int NO_CATEGORY = 0;

    public String encode(int categoryId, int sortOrder, int id) {
        String raw = VERSION + ":" + categoryId + ":" + sortOrder + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Возвращает [sortOrder, id] или 400 invalid_cursor; фильтр должен совпасть. */
    public int[] decode(String cursor, int requestedCategoryId) {
        String[] parts = decodeParts(cursor);
        try {
            int categoryId = Integer.parseInt(parts[1]);
            int sortOrder = Integer.parseInt(parts[2]);
            int id = Integer.parseInt(parts[3]);
            if (categoryId != requestedCategoryId || sortOrder < -1 || id < 0) {
                throw invalid();
            }
            return new int[]{sortOrder, id};
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    public int noCategory() {
        return NO_CATEGORY;
    }

    private String[] decodeParts(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw invalid();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(":");
            if (parts.length != 4 || !VERSION.equals(parts[0])) {
                throw invalid();
            }
            return parts;
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_cursor");
    }
}
