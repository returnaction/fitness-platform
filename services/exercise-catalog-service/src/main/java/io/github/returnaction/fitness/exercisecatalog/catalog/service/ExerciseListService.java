package io.github.returnaction.fitness.exercisecatalog.catalog.service;

import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseListItem;
import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseListResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogExerciseEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.exception.InvalidPaginationException;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogExerciseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExerciseListService {

    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;

    private final CatalogExerciseRepository exercises;
    private final ExerciseCursorCodec cursorCodec;

    @Transactional(readOnly = true)
    public ExerciseListResponse list(Short categoryId, int limit, String cursorToken) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new InvalidPaginationException(limit);
        }
        Integer filterCategoryId = categoryId == null ? null : categoryId.intValue();
        ExerciseCursor cursor = cursorCodec.decode(cursorToken, filterCategoryId);
        Integer cursorSortOrder = cursor == null ? null : cursor.sortOrder();
        Short cursorId = cursor == null ? null : (short) cursor.id();

        var rows = exercises.findActivePage(categoryId, cursorSortOrder, cursorId,
            PageRequest.of(0, limit + 1));
        boolean hasNext = rows.size() > limit;
        var page = hasNext ? rows.subList(0, limit) : rows;
        String nextCursor = hasNext ? nextCursor(filterCategoryId, page.get(page.size() - 1)) : null;

        var items = page.stream()
            .map(exercise -> new ExerciseListItem(exercise.getId(), exercise.getCode(), exercise.getName(),
                exercise.getPrimaryMetric()))
            .toList();
        return new ExerciseListResponse(items, nextCursor);
    }

    private String nextCursor(Integer categoryId, CatalogExerciseEntity last) {
        return cursorCodec.encode(categoryId, last.getSortOrder(), last.getId());
    }
}
