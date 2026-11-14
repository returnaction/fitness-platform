package io.github.returnaction.fitness.exercisecatalog.catalog.service;

import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseListItem;
import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseListResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogExerciseEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.pagination.ExerciseCursorCodec;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogExerciseRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Публичный список упражнений: активные упражнения с активной вариацией
 * STANDARD, порядок (sort_order, id), ключевая пагинация по курсору.
 */
@Service
public class ExerciseListService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private final CatalogExerciseRepository exercises;
    private final ExerciseCursorCodec cursorCodec;

    public ExerciseListService(CatalogExerciseRepository exercises, ExerciseCursorCodec cursorCodec) {
        this.exercises = exercises;
        this.cursorCodec = cursorCodec;
    }

    @Transactional(readOnly = true)
    public ExerciseListResponse list(Integer categoryId, Integer limit, String cursor) {
        int effectiveCategory = categoryId == null ? cursorCodec.noCategory() : categoryId;
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (effectiveLimit < 1 || effectiveLimit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_limit");
        }
        int[] key = cursor == null ? new int[]{-1, 0} : cursorCodec.decode(cursor, effectiveCategory);

        List<CatalogExerciseEntity> fetched = load(effectiveCategory, key, effectiveLimit + 1);
        boolean hasMore = fetched.size() > effectiveLimit;
        List<CatalogExerciseEntity> items = hasMore ? fetched.subList(0, effectiveLimit) : fetched;
        String nextCursor = hasMore
                ? cursorCodec.encode(effectiveCategory,
                        items.getLast().getSortOrder(), items.getLast().getId())
                : null;

        return new ExerciseListResponse(toItems(items), nextCursor);
    }

    private List<CatalogExerciseEntity> load(int categoryId, int[] key, int size) {
        PageRequest page = PageRequest.of(0, size);
        if (categoryId == cursorCodec.noCategory()) {
            return exercises.findActivePage(key[0], key[1], page);
        }
        return exercises.findActivePageByCategory(categoryId, key[0], key[1], page);
    }

    private List<ExerciseListItem> toItems(List<CatalogExerciseEntity> entities) {
        return entities.stream()
                .map(exercise -> new ExerciseListItem(
                        exercise.getId(),
                        exercise.getCategoryId(),
                        exercise.getCode(),
                        exercise.getName(),
                        exercise.getDescription(),
                        exercise.getPrimaryMetric().name(),
                        exercise.getSortOrder()))
                .toList();
    }
}
