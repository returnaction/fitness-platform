package io.github.returnaction.fitness.exercisecatalog.catalog.domain;

/**
 * Метрики каталога v1 и их однозначные единицы измерения.
 * Расширение требует согласования схемы снимка всеми получателями.
 */
public enum PrimaryMetric {
    REPETITIONS(MetricUnit.REP),
    DURATION(MetricUnit.SECOND);

    private final MetricUnit unit;

    PrimaryMetric(MetricUnit unit) {
        this.unit = unit;
    }

    public MetricUnit unit() {
        return unit;
    }
}
