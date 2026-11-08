package io.github.returnaction.fitness.identity.registration.entity.operation;

public enum RegistrationOperationStatus {
    /** Регистрация начата, но её технические шаги ещё не завершены. */
    PENDING,

    /** Все обязательные шаги регистрации успешно завершены. */
    COMPLETED
}
