package io.github.returnaction.fitness.challenge.settlement;

/**
 * Итог обработки события расчёта. `QUARANTINED` означает, что событие
 * подтверждено, но не применено и требует разбора.
 */
public enum SettlementApplyResult {
    APPLIED,
    DUPLICATE,
    QUARANTINED
}
