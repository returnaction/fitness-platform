package io.github.returnaction.fitness.exercisecatalog.catalog.repository;

import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogExerciseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogExerciseRepository extends JpaRepository<CatalogExerciseEntity, Integer> {
}
