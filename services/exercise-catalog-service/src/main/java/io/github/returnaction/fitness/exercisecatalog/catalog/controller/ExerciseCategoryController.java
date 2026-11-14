package io.github.returnaction.fitness.exercisecatalog.catalog.controller;

import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseCategoryResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseListResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.service.ExerciseCategoryListService;
import io.github.returnaction.fitness.exercisecatalog.catalog.service.ExerciseListService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Публичное чтение каталога через Gateway: пользовательский JWT валидирует
 * Gateway; Catalog не разбирает токены. Ограничение «только Gateway» — T013/D003.
 */
@RestController
@RequestMapping("/api/v1")
public class ExerciseCategoryController {

    private final ExerciseCategoryListService categories;
    private final ExerciseListService exercises;

    public ExerciseCategoryController(
            ExerciseCategoryListService categories,
            ExerciseListService exercises) {
        this.categories = categories;
        this.exercises = exercises;
    }

    @GetMapping(value = "/exercise-categories", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<ExerciseCategoryResponse>> listActive() {
        return ResponseEntity.ok(categories.listActive());
    }

    @GetMapping(value = "/exercises", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ExerciseListResponse> listExercises(
            @RequestParam(name = "category_id", required = false) Integer categoryId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return ResponseEntity.ok(exercises.list(categoryId, limit, cursor));
    }
}
