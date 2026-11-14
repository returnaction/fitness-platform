package io.github.returnaction.fitness.exercisecatalog.catalog.service;

import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseCategoryResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogCategoryRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for listing active exercise categories.
 * Used by ExerciseCategoryController; reads from datasource only via integration tests.
 */
@Service
public class ExerciseCategoryListService {

    private final CatalogCategoryRepository categories;

    public ExerciseCategoryListService(CatalogCategoryRepository categories) {
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public List<ExerciseCategoryResponse> listActive() {
        return categories.findAllByIsActiveTrueOrderBySortOrderAscIdAsc().stream()
                .map(category -> new ExerciseCategoryResponse(
                        category.getId(),
                        category.getCode(),
                        category.getName(),
                        category.getSortOrder()))
                .toList();
    }
}
