package io.github.returnaction.fitness.exercisecatalog.catalog.controller;

import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseCategoryListResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.service.ExerciseCategoryListService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exercise-categories")
@RequiredArgsConstructor
public class ExerciseCategoryController {

    private final ExerciseCategoryListService service;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ExerciseCategoryListResponse activeCategories() {
        return service.activeCategories();
    }
}
