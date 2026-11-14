package io.github.returnaction.fitness.exercisecatalog.catalog.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.returnaction.fitness.exercisecatalog.catalog.domain.MetricUnit;
import io.github.returnaction.fitness.exercisecatalog.catalog.domain.PrimaryMetric;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogCategoryEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogExerciseEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogVariationEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogCategoryRepository;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogExerciseRepository;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogVariationRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit-тесты загрузки цепочки: NOT_FOUND, чужая пара ID и успешный снимок. */
class ValidateChallengeTargetUseCaseTest {

    private CatalogVariationRepository variations;
    private CatalogExerciseRepository exercises;
    private CatalogCategoryRepository categories;
    private ValidateChallengeTargetUseCase useCase;

    @BeforeEach
    void setUp() {
        variations = mock(CatalogVariationRepository.class);
        exercises = mock(CatalogExerciseRepository.class);
        categories = mock(CatalogCategoryRepository.class);
        useCase = new ValidateChallengeTargetUseCase(variations, exercises, categories, new CatalogTargetValidator());
    }

    @Test
    void returnsNotFoundForUnknownVariationOrChain() {
        when(variations.findById(anyInt())).thenReturn(Optional.empty());
        assertThat(useCase.check(1, 999, 10).status()).isEqualTo(ChallengeTargetStatus.NOT_FOUND);

        stubVariation(2, 1);
        when(exercises.findById(anyInt())).thenReturn(Optional.empty());
        assertThat(useCase.check(1, 2, 10).status()).isEqualTo(ChallengeTargetStatus.NOT_FOUND);

        stubExercise(1);
        when(categories.findById(anyInt())).thenReturn(Optional.empty());
        assertThat(useCase.check(1, 2, 10).status()).isEqualTo(ChallengeTargetStatus.NOT_FOUND);
    }

    @Test
    void returnsNotFoundForMismatchedPair() {
        stubVariation(3, 7);
        stubExercise(7);
        stubCategory(1);
        assertThat(useCase.check(1, 3, 10).status()).isEqualTo(ChallengeTargetStatus.NOT_FOUND);
    }

    @Test
    void returnsSnapshotForValidTarget() {
        stubVariation(2, 7);
        stubExercise(7);
        stubCategory(1);

        TargetValidationResult result = useCase.check(7, 2, 50);

        assertThat(result.status()).isEqualTo(ChallengeTargetStatus.OK);
        VariationSnapshot snapshot = result.snapshot();
        assertThat(snapshot.exerciseId()).isEqualTo(7);
        assertThat(snapshot.exerciseName()).isEqualTo("Отжимания");
        assertThat(snapshot.exerciseVersion()).isEqualTo(1L);
        assertThat(snapshot.variationId()).isEqualTo(2);
        assertThat(snapshot.variationName()).isEqualTo("Обычные");
        assertThat(snapshot.variationVersion()).isEqualTo(1L);
        assertThat(snapshot.description()).isEqualTo("Описание.");
        assertThat(snapshot.instructions()).isEqualTo("Инструкция.");
        assertThat(snapshot.metric()).isEqualTo("REPETITIONS");
        assertThat(snapshot.unit()).isEqualTo(MetricUnit.REP);
        assertThat(snapshot.targetValue()).isEqualTo(50);
    }

    private void stubVariation(int id, int exerciseId) {
        CatalogVariationEntity variation = mock(CatalogVariationEntity.class);
        when(variation.getId()).thenReturn(id);
        when(variation.getExerciseId()).thenReturn(exerciseId);
        when(variation.getIsActive()).thenReturn(true);
        when(variation.getMinTarget()).thenReturn(1L);
        when(variation.getMaxTarget()).thenReturn(200L);
        when(variation.getTargetStep()).thenReturn(1L);
        when(variation.getVersion()).thenReturn(1L);
        when(variation.getName()).thenReturn("Обычные");
        when(variation.getDescription()).thenReturn("Описание.");
        when(variation.getInstructions()).thenReturn("Инструкция.");
        when(variations.findById(id)).thenReturn(Optional.of(variation));
    }

    private void stubExercise(int id) {
        CatalogExerciseEntity exercise = mock(CatalogExerciseEntity.class);
        when(exercise.getId()).thenReturn(id);
        when(exercise.getCategoryId()).thenReturn(1);
        when(exercise.getIsActive()).thenReturn(true);
        when(exercise.getVersion()).thenReturn(1L);
        when(exercise.getName()).thenReturn("Отжимания");
        when(exercise.getPrimaryMetric()).thenReturn(PrimaryMetric.REPETITIONS);
        when(exercises.findById(id)).thenReturn(Optional.of(exercise));
    }

    private void stubCategory(int id) {
        CatalogCategoryEntity category = mock(CatalogCategoryEntity.class);
        when(category.getId()).thenReturn(id);
        when(category.getIsActive()).thenReturn(true);
        when(categories.findById(id)).thenReturn(Optional.of(category));
    }
}
