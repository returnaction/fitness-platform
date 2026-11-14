package io.github.returnaction.fitness.exercisecatalog.catalog.entity;

import io.github.returnaction.fitness.exercisecatalog.catalog.domain.PrimaryMetric;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "catalog_exercises")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CatalogExerciseEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Short id;

    @Column(name = "category_id", nullable = false)
    private Short categoryId;

    @Column(name = "code", nullable = false, updatable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "primary_metric", nullable = false, length = 16)
    private PrimaryMetric primaryMetric;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
