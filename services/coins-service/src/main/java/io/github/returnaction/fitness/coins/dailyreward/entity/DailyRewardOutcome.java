package io.github.returnaction.fitness.coins.dailyreward.entity;

public enum DailyRewardOutcome {
    GRANTED,          // Подарок начислен: монеты добавлены в доступный баланс.
    NOT_YET_AVAILABLE // Подарок пока недоступен: с прошлого получения ещё не прошло 24 часа, монеты не начислены.
}
