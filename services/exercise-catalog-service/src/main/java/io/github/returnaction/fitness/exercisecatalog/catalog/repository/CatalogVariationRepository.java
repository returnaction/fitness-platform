package io.github.returnaction.fitness.exercisecatalog.catalog.repository;

import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogVariationEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogVariationRepository extends JpaRepository<CatalogVariationEntity, Integer> {

    /** Активные вариации упражнения в порядке (sort_order, id). */
    List<CatalogVariationEntity> findByExerciseIdAndIsActiveTrueOrderBySortOrderAscIdAsc(Integer exerciseId);
}
