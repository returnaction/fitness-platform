package io.github.returnaction.fitness.exercisecatalog.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Категория справочника. В MVP каталог только читается; данные приходят из Liquibase.
 */
@Entity
@Table(name = "catalog_categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CatalogCategoryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Short id;

    @Column(name = "code", nullable = false, updatable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

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
