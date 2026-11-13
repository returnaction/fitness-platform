package io.github.returnaction.fitness.exercisecatalog.catalog.repository;

import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogCategoryRepository extends JpaRepository<CatalogCategoryEntity, Integer> {
}
