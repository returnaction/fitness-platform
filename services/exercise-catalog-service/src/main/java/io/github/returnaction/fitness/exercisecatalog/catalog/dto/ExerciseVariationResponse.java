package io.github.returnaction.fitness.exercisecatalog.catalog.dto;

public record ExerciseVariationResponse(int id,
                                        String code,
                                        String name,
                                        String description,
                                        String instructions,
                                        long minTarget,
                                        long maxTarget,
                                        long targetStep) {
}
