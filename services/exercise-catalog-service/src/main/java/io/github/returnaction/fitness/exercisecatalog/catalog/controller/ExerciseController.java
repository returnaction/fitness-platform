package io.github.returnaction.fitness.exercisecatalog.catalog.controller;

import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseDetailResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseListResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.service.ExerciseDetailService;
import io.github.returnaction.fitness.exercisecatalog.catalog.service.ExerciseListService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exercises")
@RequiredArgsConstructor
public class ExerciseController {

    private final ExerciseListService listService;
    private final ExerciseDetailService detailService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ExerciseListResponse list(@RequestParam(name = "category_id", required = false) Short categoryId,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(required = false) String cursor) {

        return listService.list(categoryId, limit, cursor);
    }

    @GetMapping(value = "/{exercise_id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExerciseDetailResponse detail(@PathVariable("exercise_id") Short exerciseId) {
        return detailService.get(exerciseId);
    }
}
