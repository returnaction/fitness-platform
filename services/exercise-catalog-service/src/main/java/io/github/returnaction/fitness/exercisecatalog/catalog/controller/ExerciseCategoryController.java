package io.github.returnaction.fitness.exercisecatalog.catalog.controller;

import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseCategoryResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.service.ExerciseCategoryListService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public read-only access to active exercise categories via Gateway only.
 * Catalog does not validate user JWT; Gateway is the single external entry point.
 * All requests from outside the internal network are blocked by Gateway (T013/D003).
 */
@RestController
@RequestMapping("/api/v1")
public class ExerciseCategoryController {

    private final ExerciseCategoryListService service;

    public ExerciseCategoryController(ExerciseCategoryListService service) {
        this.service = service;
    }

    @GetMapping(value = "/exercise-categories", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<ExerciseCategoryResponse>> listActive() {
        return ResponseEntity.ok(service.listActive());
    }
}
