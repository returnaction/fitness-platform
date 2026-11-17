package io.github.returnaction.fitness.coins.settlement;

/**
 * Итог обработки `challenge.finished`. `QUARANTINED` означает, что событие
 * подтверждено, но не применено и требует разбора.
 */
public enum SettlementApplyResult {
    APPLIED,
    DUPLICATE,
    QUARANTINED
}
