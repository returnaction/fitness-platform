package io.github.returnaction.fitness.exercisecatalog.catalog.exception;

/**
 * Базовое исключение проверки цели по каталогу.
 *
 * <p>Все отказы бизнес-проверки цели имеют тип исключения: вызывающий код
 * различает их без разбора текста сообщения. Идентификаторы каталога нужны для
 * безопасного логирования и будущего сопоставления с gRPC-статусом.
 */
public abstract class CatalogTargetException extends RuntimeException {

    private final long exerciseId;
    private final long variationId;

    protected CatalogTargetException(String message, long exerciseId, long variationId) {
        super(message);
        this.exerciseId = exerciseId;
        this.variationId = variationId;
    }

    public long getExerciseId() {
        return exerciseId;
    }

    public long getVariationId() {
        return variationId;
    }
}
