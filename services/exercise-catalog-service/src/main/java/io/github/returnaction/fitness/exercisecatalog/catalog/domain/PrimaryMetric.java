package io.github.returnaction.fitness.exercisecatalog.catalog.domain;

/**
 * Метрика упражнения из каталога v1.
 *
 * <p>Единица измерения — часть контракта: {@code REPETITIONS} всегда {@code REP},
 * {@code DURATION} всегда {@code SECOND}. Клиент не выбирает единицу сам.
 */
public enum PrimaryMetric {

    REPETITIONS("REP"),
    DURATION("SECOND");

    private final String unit;

    PrimaryMetric(String unit) {
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }
}
