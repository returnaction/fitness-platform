package io.github.returnaction.fitness.exercisecatalog.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.returnaction.fitness.exercisecatalog.catalog.domain.PrimaryMetric;
import io.github.returnaction.fitness.exercisecatalog.catalog.exception.InactiveCatalogTargetException;
import io.github.returnaction.fitness.exercisecatalog.catalog.exception.TargetOutOfRangeException;
import io.github.returnaction.fitness.exercisecatalog.catalog.exception.TargetStepInvalidException;
import org.junit.jupiter.api.Test;

class CatalogTargetValidatorTest {

    private final CatalogTargetValidator validator = new CatalogTargetValidator();

    @Test
    void acceptsRepetitionsInsideRangeAndReturnsCatalogUnit() {
        ResolvedCatalogTarget target = target(PrimaryMetric.REPETITIONS, true, true, true, 1, 200, 1);

        ValidatedTarget validated = validator.validate(target, 10);

        assertThat(validated.exerciseId()).isEqualTo(1);
        assertThat(validated.variationId()).isEqualTo(2);
        assertThat(validated.primaryMetric()).isEqualTo(PrimaryMetric.REPETITIONS);
        assertThat(validated.targetValue()).isEqualTo(10);
        assertThat(validated.unit()).isEqualTo("REP");
    }

    @Test
    void acceptsDurationInsideRangeAndReturnsSecondUnit() {
        ResolvedCatalogTarget target = target(PrimaryMetric.DURATION, true, true, true, 10, 600, 1);

        ValidatedTarget validated = validator.validate(target, 60);

        assertThat(validated.primaryMetric()).isEqualTo(PrimaryMetric.DURATION);
        assertThat(validated.unit()).isEqualTo("SECOND");
    }

    @Test
    void acceptsTargetThatMatchesStep() {
        ResolvedCatalogTarget target = target(PrimaryMetric.REPETITIONS, true, true, true, 2, 200, 2);

        assertThat(validator.validate(target, 10).targetValue()).isEqualTo(10);
    }

    @Test
    void rejectsInactiveCategory() {
        ResolvedCatalogTarget target = target(PrimaryMetric.REPETITIONS, false, true, true, 1, 200, 1);

        assertThatThrownBy(() -> validator.validate(target, 10))
            .isInstanceOfSatisfying(InactiveCatalogTargetException.class, exception -> {
                assertThat(exception.getExerciseId()).isEqualTo(1);
                assertThat(exception.getVariationId()).isEqualTo(2);
            });
    }

    @Test
    void rejectsInactiveExercise() {
        ResolvedCatalogTarget target = target(PrimaryMetric.REPETITIONS, true, false, true, 1, 200, 1);

        assertThatThrownBy(() -> validator.validate(target, 10))
            .isInstanceOf(InactiveCatalogTargetException.class);
    }

    @Test
    void rejectsInactiveVariation() {
        ResolvedCatalogTarget target = target(PrimaryMetric.REPETITIONS, true, true, false, 1, 200, 1);

        assertThatThrownBy(() -> validator.validate(target, 10))
            .isInstanceOf(InactiveCatalogTargetException.class);
    }

    @Test
    void rejectsTargetBelowMinimum() {
        ResolvedCatalogTarget target = target(PrimaryMetric.DURATION, true, true, true, 10, 600, 1);

        assertThatThrownBy(() -> validator.validate(target, 9))
            .isInstanceOfSatisfying(TargetOutOfRangeException.class, exception -> {
                assertThat(exception.getTargetValue()).isEqualTo(9);
                assertThat(exception.getMinTarget()).isEqualTo(10);
                assertThat(exception.getMaxTarget()).isEqualTo(600);
            });
    }

    @Test
    void rejectsTargetAboveMaximum() {
        ResolvedCatalogTarget target = target(PrimaryMetric.REPETITIONS, true, true, true, 1, 200, 1);

        assertThatThrownBy(() -> validator.validate(target, 201))
            .isInstanceOf(TargetOutOfRangeException.class);
    }

    @Test
    void rejectsZeroAndNegativeTargetAsOutOfRange() {
        ResolvedCatalogTarget target = target(PrimaryMetric.REPETITIONS, true, true, true, 1, 200, 1);

        assertThatThrownBy(() -> validator.validate(target, 0))
            .isInstanceOf(TargetOutOfRangeException.class);
        assertThatThrownBy(() -> validator.validate(target, -5))
            .isInstanceOf(TargetOutOfRangeException.class);
    }

    @Test
    void rejectsTargetThatDoesNotMatchStep() {
        ResolvedCatalogTarget target = target(PrimaryMetric.REPETITIONS, true, true, true, 2, 200, 2);

        assertThatThrownBy(() -> validator.validate(target, 3))
            .isInstanceOfSatisfying(TargetStepInvalidException.class, exception -> {
                assertThat(exception.getTargetValue()).isEqualTo(3);
                assertThat(exception.getTargetStep()).isEqualTo(2);
            });
    }

    private ResolvedCatalogTarget target(PrimaryMetric metric, boolean categoryActive, boolean exerciseActive,
        boolean variationActive, long minTarget, long maxTarget, long targetStep) {

        return new ResolvedCatalogTarget(1, 2, categoryActive, exerciseActive, variationActive, metric,
            minTarget, maxTarget, targetStep);
    }
}
