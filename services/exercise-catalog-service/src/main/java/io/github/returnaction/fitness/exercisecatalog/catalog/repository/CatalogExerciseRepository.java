package io.github.returnaction.fitness.exercisecatalog.catalog.repository;

import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogExerciseEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogExerciseRepository extends JpaRepository<CatalogExerciseEntity, Integer> {

    /**
     * Ключевая пагинация по (sort_order, id) среди активных упражнений
     * с активной вариацией STANDARD; для первой страницы sortOrder = -1, id = 0.
     */
    @Query("""
            SELECT e FROM CatalogExerciseEntity e
            WHERE e.isActive = true
              AND (e.sortOrder > :sortOrder OR (e.sortOrder = :sortOrder AND e.id > :id))
              AND EXISTS (SELECT 1 FROM CatalogVariationEntity v
                          WHERE v.exerciseId = e.id AND v.isActive = true AND v.code = 'STANDARD')
            ORDER BY e.sortOrder ASC, e.id ASC
            """)
    List<CatalogExerciseEntity> findActivePage(@Param("sortOrder") int sortOrder,
                                               @Param("id") int id,
                                               Pageable pageable);

    /** То же с фильтром по категории; cursor привязывается к этому фильтру. */
    @Query("""
            SELECT e FROM CatalogExerciseEntity e
            WHERE e.isActive = true
              AND e.categoryId = :categoryId
              AND (e.sortOrder > :sortOrder OR (e.sortOrder = :sortOrder AND e.id > :id))
              AND EXISTS (SELECT 1 FROM CatalogVariationEntity v
                          WHERE v.exerciseId = e.id AND v.isActive = true AND v.code = 'STANDARD')
            ORDER BY e.sortOrder ASC, e.id ASC
            """)
    List<CatalogExerciseEntity> findActivePageByCategory(@Param("categoryId") int categoryId,
                                                         @Param("sortOrder") int sortOrder,
                                                         @Param("id") int id,
                                                         Pageable pageable);
}
