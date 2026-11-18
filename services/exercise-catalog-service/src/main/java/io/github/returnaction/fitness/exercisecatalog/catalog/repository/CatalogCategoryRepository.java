package io.github.returnaction.fitness.exercisecatalog.catalog.repository;

import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CatalogCategoryRepository extends JpaRepository<CatalogCategoryEntity, Short> {

    List<CatalogCategoryEntity> findAllByActiveTrueOrderBySortOrderAscIdAsc();
}
