package io.github.returnaction.fitness.exercisecatalog.catalog.entity;

import io.github.returnaction.fitness.exercisecatalog.catalog.domain.PrimaryMetric;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Упражнение каталога; таблица catalog_exercises, данные поставляются Liquibase. */
@Entity
@Table(name = "catalog_exercises")
public class CatalogExerciseEntity {

    @Id
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "id")
    private Integer id;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "category_id", nullable = false, insertable = false, updatable = false)
    private Integer categoryId;

    @Column(name = "code", nullable = false, insertable = false, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, insertable = false, updatable = false)
    private String name;

    @Column(name = "description", nullable = false, insertable = false, updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "primary_metric", nullable = false, insertable = false, updatable = false)
    private PrimaryMetric primaryMetric;

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

    public Integer getCategoryId() {
        return categoryId;
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

    public PrimaryMetric getPrimaryMetric() {
        return primaryMetric;
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
