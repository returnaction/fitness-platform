package io.github.returnaction.fitness.exercisecatalog.catalog.repository;

import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogExerciseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CatalogExerciseRepository extends JpaRepository<CatalogExerciseEntity, Short> {

    List<CatalogExerciseEntity> findAllByActiveTrueOrderBySortOrderAscIdAsc();
}
