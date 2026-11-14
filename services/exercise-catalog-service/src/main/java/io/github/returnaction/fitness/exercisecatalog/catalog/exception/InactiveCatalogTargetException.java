package io.github.returnaction.fitness.exercisecatalog.catalog.exception;

/**
 * Категория, упражнение или вариация выключены: цель по такой цепочке недопустима.
 */
public final class InactiveCatalogTargetException extends CatalogTargetException {

    public InactiveCatalogTargetException(long exerciseId, long variationId) {
        super("Catalog target is inactive.", exerciseId, variationId);
    }
}
