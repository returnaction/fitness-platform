package io.github.returnaction.fitness.challenge.domain;

/**
 * Изменения черновика с признаками присутствия полей: отсутствие поля не
 * меняет значение, явный null очищает nullable-поле.
 */
public record DraftChanges(String title, boolean titlePresent,
                           String description, boolean descriptionPresent,
                           Long stakeAmount, boolean stakeAmountPresent,
                           Integer durationDays, boolean durationDaysPresent) {
}
