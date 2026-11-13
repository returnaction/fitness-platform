package io.github.returnaction.fitness.exercisecatalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.returnaction.fitness.exercisecatalog.catalog.domain.MetricUnit;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogExerciseRepository;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogVariationRepository;
import io.github.returnaction.fitness.exercisecatalog.catalog.validation.ChallengeTargetStatus;
import io.github.returnaction.fitness.exercisecatalog.catalog.validation.ValidateChallengeTargetUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL-интеграционный тест связки «сущности ↔ схема» и use case на
 * seed-данных: Liquibase применяет миграции, Hibernate валидирует маппинг,
 * затем выполняется реальная проверка цели.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class CatalogJpaMappingIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Возвращаем auto-конфигурации, отключённые в test-профиле:
        // Liquibase применяет миграции, Hibernate валидирует сущности по схеме.
        registry.add("spring.autoconfigure.exclude", () -> "");
        registry.add("spring.liquibase.enabled", () -> "true");
    }

    @Autowired
    private ValidateChallengeTargetUseCase useCase;

    @Autowired
    private CatalogExerciseRepository exercises;

    @Autowired
    private CatalogVariationRepository variations;

    @Test
    void validatesSeedDataAndTarget() {
        assertThat(exercises.count()).isEqualTo(6);
        assertThat(variations.count()).isEqualTo(12);

        var result = useCase.check(1, 1, 50);
        assertThat(result.status()).isEqualTo(ChallengeTargetStatus.OK);
        var snapshot = result.snapshot();
        assertThat(snapshot.metric()).isEqualTo("REPETITIONS");
        assertThat(snapshot.unit()).isEqualTo(MetricUnit.REP);

        var plank = exercises.findById(6).orElseThrow();
        assertThat(plank.getPrimaryMetric().name()).isEqualTo("DURATION");

        assertThat(useCase.check(1, 1, 1000).status()).isEqualTo(ChallengeTargetStatus.TARGET_OUT_OF_RANGE);
        // Чужая пара exercise/variation и неизвестный ID не раскрывают каталог.
        assertThat(useCase.check(2, 1, 50).status()).isEqualTo(ChallengeTargetStatus.NOT_FOUND);
        assertThat(useCase.check(1, 999, 50).status()).isEqualTo(ChallengeTargetStatus.NOT_FOUND);
    }
}
