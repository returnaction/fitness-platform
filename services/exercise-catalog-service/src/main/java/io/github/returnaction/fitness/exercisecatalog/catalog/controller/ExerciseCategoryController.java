package io.github.returnaction.fitness.exercisecatalog.catalog.controller;

import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseCategoryResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseDetailResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseListResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.service.ExerciseCategoryListService;
import io.github.returnaction.fitness.exercisecatalog.catalog.service.ExerciseDetailService;
import io.github.returnaction.fitness.exercisecatalog.catalog.service.ExerciseListService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final ExerciseListService exerciseList;
    private final ExerciseDetailService exerciseDetail;

    public ExerciseCategoryController(
            ExerciseCategoryListService categories,
            ExerciseListService exerciseList,
            ExerciseDetailService exerciseDetail) {
        this.categories = categories;
        this.exerciseList = exerciseList;
        this.exerciseDetail = exerciseDetail;
    }

    @GetMapping(value = "/exercise-categories", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<ExerciseCategoryResponse>> listActive() {
        return ResponseEntity.ok(categories.listActive());
    }

    @GetMapping(value = "/exercises/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ExerciseDetailResponse> exerciseDetail(@PathVariable("id") int id) {
        return ResponseEntity.ok(exerciseDetail.detail(id));
    }

    @GetMapping(value = "/exercises", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ExerciseListResponse> listExercises(
            @RequestParam(name = "category_id", required = false) Integer categoryId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return ResponseEntity.ok(exerciseList.list(categoryId, limit, cursor));
    }
}
