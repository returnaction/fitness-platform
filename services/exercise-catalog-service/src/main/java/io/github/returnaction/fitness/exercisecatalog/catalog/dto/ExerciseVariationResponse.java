package io.github.returnaction.fitness.exercisecatalog.catalog.dto;

/** Активная вариация в карточке упражнения: инструкция и границы цели. */
public record ExerciseVariationResponse(
        int id,
        String code,
        String name,
        String description,
        String instructions,
        long minTarget,
        long maxTarget,
        long targetStep,
        int sortOrder) {
}
