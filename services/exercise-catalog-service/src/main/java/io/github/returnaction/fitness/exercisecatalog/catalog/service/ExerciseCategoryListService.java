package io.github.returnaction.fitness.exercisecatalog.catalog.service;

import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseCategoryListResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseCategoryResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExerciseCategoryListService {

    private final CatalogCategoryRepository categories;

    @Transactional(readOnly = true)
    public ExerciseCategoryListResponse activeCategories() {
        var items = categories.findAllByIsActiveTrueOrderBySortOrderAscIdAsc()
            .stream()
            .map(category -> new ExerciseCategoryResponse(category.getId(), category.getCode(), category.getName()))
            .toList();
        return new ExerciseCategoryListResponse(items);
    }
}
