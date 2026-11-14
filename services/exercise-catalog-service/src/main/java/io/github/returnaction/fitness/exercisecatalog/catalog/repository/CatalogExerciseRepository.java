package io.github.returnaction.fitness.exercisecatalog.catalog.repository;

import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogExerciseEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogExerciseRepository extends JpaRepository<CatalogExerciseEntity, Short> {

    @Query("""
        select e from CatalogExerciseEntity e
        where e.isActive = true
          and (:categoryId is null or e.categoryId = :categoryId)
          and exists (
              select v.id from CatalogVariationEntity v
              where v.exerciseId = e.id and v.code = 'STANDARD' and v.isActive = true
          )
          and (
              :cursorSortOrder is null
              or e.sortOrder > :cursorSortOrder
              or (e.sortOrder = :cursorSortOrder and e.id > :cursorId)
          )
        order by e.sortOrder asc, e.id asc
        """)
    List<CatalogExerciseEntity> findActivePage(@Param("categoryId") Short categoryId,
                                                @Param("cursorSortOrder") Integer cursorSortOrder,
                                                @Param("cursorId") Short cursorId,
                                                Pageable pageable);
}
