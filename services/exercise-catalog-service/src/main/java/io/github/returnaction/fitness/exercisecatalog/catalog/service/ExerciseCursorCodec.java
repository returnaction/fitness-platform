package io.github.returnaction.fitness.exercisecatalog.catalog.service;

import io.github.returnaction.fitness.exercisecatalog.catalog.exception.InvalidCursorException;
import java.util.Base64;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Кодирует и разбирает непрозрачный cursor списка упражнений.
 *
 * <p>Формат: Base64URL без padding от JSON
 * {@code {"v":1,"c":categoryId|null,"s":sortOrder,"i":id}}. Cursor не является
 * разрешением: запрос всё равно фильтрует активные записи и активный STANDARD.
 */
@Component
@RequiredArgsConstructor
public class ExerciseCursorCodec {

    private static final int VERSION = 1;

    private final ObjectMapper objectMapper;

    public String encode(Integer categoryId, int sortOrder, int id) {
        CursorPayload payload = new CursorPayload(VERSION, categoryId, sortOrder, id);
        try {
            byte[] json = objectMapper.writeValueAsBytes(payload);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot encode catalog cursor.", exception);
        }
    }

    public ExerciseCursor decode(String token, Integer expectedCategoryId) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(token);
            JsonNode node = objectMapper.readTree(json);
            if (node == null || node.get("v") == null || node.get("v").asInt() != VERSION) {
                throw new InvalidCursorException();
            }
            Integer categoryId = readCategory(node.get("c"));
            int sortOrder = readRequiredInt(node.get("s"));
            int id = readRequiredInt(node.get("i"));
            if (sortOrder < 0 || id < 1 || id > Short.MAX_VALUE) {
                throw new InvalidCursorException();
            }
            if (!Objects.equals(categoryId, expectedCategoryId)) {
                throw new InvalidCursorException();
            }
            return new ExerciseCursor(categoryId, sortOrder, id);
        } catch (IllegalArgumentException | JacksonException exception) {
            throw new InvalidCursorException();
        }
    }

    private Integer readCategory(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        int value = readRequiredInt(node);
        if (value < 1 || value > Short.MAX_VALUE) {
            throw new InvalidCursorException();
        }
        return value;
    }

    private int readRequiredInt(JsonNode node) {
        if (node == null || node.isNull() || !node.canConvertToInt()) {
            throw new InvalidCursorException();
        }
        return node.asInt();
    }

    private record CursorPayload(int v, Integer c, int s, int i) {
    }
}
