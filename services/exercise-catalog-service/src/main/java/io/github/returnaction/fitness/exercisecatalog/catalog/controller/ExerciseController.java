package io.github.returnaction.fitness.exercisecatalog.catalog.controller;

import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseListResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.service.ExerciseListService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exercises")
@RequiredArgsConstructor
public class ExerciseController {

    private final ExerciseListService service;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ExerciseListResponse list(@RequestParam(name = "category_id", required = false) Short categoryId,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(required = false) String cursor) {

        return service.list(categoryId, limit, cursor);
    }
}
