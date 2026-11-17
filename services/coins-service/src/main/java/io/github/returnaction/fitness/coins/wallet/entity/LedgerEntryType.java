package io.github.returnaction.fitness.coins.wallet.entity;

public enum LedgerEntryType {
    DAILY_REWARD_CREDIT,      // Начисление ежедневного подарка в доступный баланс.
    CHALLENGE_STAKE_LOCK,     // Перевод ставки из доступного баланса в резерв соревнования.
    CHALLENGE_STAKE_RELEASE,  // Возврат своей ставки из резерва в доступный баланс.
    CHALLENGE_STAKE_FORFEIT,  // Списание проигранной ставки из резерва.
    CHALLENGE_WIN_CREDIT,     // Начисление выигрыша в доступный баланс, отдельно от возврата своей ставки.
    ADMIN_ADJUSTMENT,         // Корректировка баланса администратором с указанием причины.
    ENTRY_REVERSAL            // Отмена ошибочной записи новым обратным движением монет со ссылкой на исходную запись.
}
