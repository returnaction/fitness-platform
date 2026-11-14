package io.github.returnaction.fitness.exercisecatalog.catalog.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.returnaction.fitness.exercisecatalog.catalog.domain.MetricUnit;
import io.github.returnaction.fitness.exercisecatalog.catalog.domain.PrimaryMetric;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogCategoryEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogExerciseEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogVariationEntity;
import org.junit.jupiter.api.Test;

/** Unit-тесты чистой проверки цели: метрика/единица, границы, шаг, неактивная цепочка. */
class CatalogTargetValidatorTest {

    private final CatalogTargetValidator validator = new CatalogTargetValidator();

    @Test
    void acceptsTargetInsideRangeForRepetitions() {
        TargetValidationResult result = validator.validate(chain(1, 200, 1, PrimaryMetric.REPETITIONS), 10);

        assertThat(result.status()).isEqualTo(ChallengeTargetStatus.OK);
        VariationSnapshot snapshot = result.snapshot();
        assertThat(snapshot.metric()).isEqualTo("REPETITIONS");
        assertThat(snapshot.unit()).isEqualTo(MetricUnit.REP);
        assertThat(snapshot.targetValue()).isEqualTo(10);
    }

    @Test
    void acceptsBoundariesAndDurationUnit() {
        TargetValidationResult atMin = validator.validate(chain(10, 600, 1, PrimaryMetric.DURATION), 10);
        assertThat(atMin.status()).isEqualTo(ChallengeTargetStatus.OK);
        assertThat(atMin.snapshot().unit()).isEqualTo(MetricUnit.SECOND);

        TargetValidationResult atMax = validator.validate(chain(10, 600, 1, PrimaryMetric.DURATION), 600);
        assertThat(atMax.status()).isEqualTo(ChallengeTargetStatus.OK);
    }

    @Test
    void rejectsNonPositiveTarget() {
        assertThat(validator.validate(chain(1, 200, 1, PrimaryMetric.REPETITIONS), 0).status())
                .isEqualTo(ChallengeTargetStatus.TARGET_OUT_OF_RANGE);
        assertThat(validator.validate(chain(1, 200, 1, PrimaryMetric.REPETITIONS), -5).status())
                .isEqualTo(ChallengeTargetStatus.TARGET_OUT_OF_RANGE);
    }

    @Test
    void rejectsTargetOutsideRange() {
        assertThat(validator.validate(chain(10, 600, 1, PrimaryMetric.DURATION), 9).status())
                .isEqualTo(ChallengeTargetStatus.TARGET_OUT_OF_RANGE);
        assertThat(validator.validate(chain(10, 600, 1, PrimaryMetric.DURATION), 601).status())
                .isEqualTo(ChallengeTargetStatus.TARGET_OUT_OF_RANGE);
    }

    @Test
    void acceptsMultipleOfStepAndRejectsOtherwise() {
        // Симметричные вариации будущих changeset-ов используют target_step = 2.
        assertThat(validator.validate(chain(2, 100, 2, PrimaryMetric.REPETITIONS), 10).status())
                .isEqualTo(ChallengeTargetStatus.OK);
        assertThat(validator.validate(chain(2, 100, 2, PrimaryMetric.REPETITIONS), 11).status())
                .isEqualTo(ChallengeTargetStatus.TARGET_STEP_INVALID);
    }

    @Test
    void rejectsInactiveChainAtAnyLevel() {
        ResolvedVariationChain inactiveVariation = chain(1, 200, 1, PrimaryMetric.REPETITIONS);
        when(inactiveVariation.variation().getIsActive()).thenReturn(false);

        ResolvedVariationChain inactiveExercise = chain(1, 200, 1, PrimaryMetric.REPETITIONS);
        when(inactiveExercise.exercise().getIsActive()).thenReturn(false);

        ResolvedVariationChain inactiveCategory = chain(1, 200, 1, PrimaryMetric.REPETITIONS);
        when(inactiveCategory.category().getIsActive()).thenReturn(false);

        assertThat(validator.validate(inactiveVariation, 10).status()).isEqualTo(ChallengeTargetStatus.INACTIVE);
        assertThat(validator.validate(inactiveExercise, 10).status()).isEqualTo(ChallengeTargetStatus.INACTIVE);
        assertThat(validator.validate(inactiveCategory, 10).status()).isEqualTo(ChallengeTargetStatus.INACTIVE);
    }

    private ResolvedVariationChain chain(int min, int max, int step, PrimaryMetric metric) {
        CatalogVariationEntity variation = mock(CatalogVariationEntity.class);
        when(variation.getIsActive()).thenReturn(true);
        when(variation.getMinTarget()).thenReturn((long) min);
        when(variation.getMaxTarget()).thenReturn((long) max);
        when(variation.getTargetStep()).thenReturn((long) step);
        when(variation.getId()).thenReturn(1);
        when(variation.getVersion()).thenReturn(1L);
        when(variation.getName()).thenReturn("Обычные");
        when(variation.getDescription()).thenReturn("Описание.");
        when(variation.getInstructions()).thenReturn("Инструкция.");

        CatalogExerciseEntity exercise = mock(CatalogExerciseEntity.class);
        when(exercise.getIsActive()).thenReturn(true);
        when(exercise.getPrimaryMetric()).thenReturn(metric);
        when(exercise.getId()).thenReturn(2);
        when(exercise.getVersion()).thenReturn(1L);
        when(exercise.getName()).thenReturn("Отжимания");

        CatalogCategoryEntity category = mock(CatalogCategoryEntity.class);
        when(category.getIsActive()).thenReturn(true);

        return new ResolvedVariationChain(variation, exercise, category);
    }
}
