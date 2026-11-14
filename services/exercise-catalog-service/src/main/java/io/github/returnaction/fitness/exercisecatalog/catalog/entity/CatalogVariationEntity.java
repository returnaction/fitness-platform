package io.github.returnaction.fitness.exercisecatalog.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "catalog_variations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CatalogVariationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Short id;

    @Column(name = "exercise_id", nullable = false)
    private Short exerciseId;

    @Column(name = "code", nullable = false, updatable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", nullable = false, length = 1000)
    private String description;

    @Column(name = "instructions", nullable = false, length = 2000)
    private String instructions;

    @Column(name = "min_target", nullable = false)
    private long minTarget;

    @Column(name = "max_target", nullable = false)
    private long maxTarget;

    @Column(name = "target_step", nullable = false)
    private long targetStep;

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
