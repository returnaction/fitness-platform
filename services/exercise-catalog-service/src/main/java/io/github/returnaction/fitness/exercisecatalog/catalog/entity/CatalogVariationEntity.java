package io.github.returnaction.fitness.exercisecatalog.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Вариация упражнения; таблица catalog_variations, данные поставляются Liquibase. */
@Entity
@Table(name = "catalog_variations")
public class CatalogVariationEntity {

    @Id
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "id")
    private Integer id;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "exercise_id", nullable = false, insertable = false, updatable = false)
    private Integer exerciseId;

    @Column(name = "code", nullable = false, insertable = false, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, insertable = false, updatable = false)
    private String name;

    @Column(name = "description", nullable = false, insertable = false, updatable = false)
    private String description;

    @Column(name = "instructions", nullable = false, insertable = false, updatable = false)
    private String instructions;

    @Column(name = "min_target", nullable = false, insertable = false, updatable = false)
    private Long minTarget;

    @Column(name = "max_target", nullable = false, insertable = false, updatable = false)
    private Long maxTarget;

    @Column(name = "target_step", nullable = false, insertable = false, updatable = false)
    private Long targetStep;

    @Column(name = "is_active", nullable = false, insertable = false, updatable = false)
    private Boolean isActive;

    @Column(name = "sort_order", nullable = false, insertable = false, updatable = false)
    private Integer sortOrder;

    @Column(name = "version", nullable = false, insertable = false, updatable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    public Integer getId() {
        return id;
    }

    public Integer getExerciseId() {
        return exerciseId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getInstructions() {
        return instructions;
    }

    public Long getMinTarget() {
        return minTarget;
    }

    public Long getMaxTarget() {
        return maxTarget;
    }

    public Long getTargetStep() {
        return targetStep;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
