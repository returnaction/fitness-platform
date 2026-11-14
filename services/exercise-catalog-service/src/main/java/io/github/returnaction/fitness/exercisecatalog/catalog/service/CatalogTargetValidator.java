package io.github.returnaction.fitness.exercisecatalog.catalog.service;

import io.github.returnaction.fitness.exercisecatalog.catalog.exception.InactiveCatalogTargetException;
import io.github.returnaction.fitness.exercisecatalog.catalog.exception.TargetOutOfRangeException;
import io.github.returnaction.fitness.exercisecatalog.catalog.exception.TargetStepInvalidException;
import org.springframework.stereotype.Component;

/**
 * Единственное место проверки цели по каталогу.
 *
 * <p>Правила не дублируются в REST-контроллере или gRPC-обработчике: оба
 * адаптера должны вызывать этот компонент. Проверяется вся активная цепочка,
 * затем диапазон и шаг. Метрика берётся из каталога, значение с клиента — нет.
 */
@Component
public final class CatalogTargetValidator {

    public ValidatedTarget validate(ResolvedCatalogTarget target, long targetValue) {
        if (!target.categoryActive() || !target.exerciseActive() || !target.variationActive()) {
            throw new InactiveCatalogTargetException(target.exerciseId(), target.variationId());
        }
        if (targetValue < target.minTarget() || targetValue > target.maxTarget()) {
            throw new TargetOutOfRangeException(target.exerciseId(), target.variationId(), targetValue,
                target.minTarget(), target.maxTarget());
        }
        if (targetValue % target.targetStep() != 0) {
            throw new TargetStepInvalidException(target.exerciseId(), target.variationId(), targetValue,
                target.targetStep());
        }
        return new ValidatedTarget(target.exerciseId(), target.variationId(), target.primaryMetric(), targetValue);
    }
}
