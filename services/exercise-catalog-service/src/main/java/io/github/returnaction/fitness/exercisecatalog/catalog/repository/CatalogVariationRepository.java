package io.github.returnaction.fitness.exercisecatalog.catalog.repository;

import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogVariationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CatalogVariationRepository extends JpaRepository<CatalogVariationEntity, Short> {

    List<CatalogVariationEntity> findAllByActiveTrueOrderBySortOrderAscIdAsc();
}
