-- Журнал операций Coins: строки только добавляются, исправления — новой записью.
CREATE TABLE coin_ledger_entries (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES coin_wallets (user_id) ON DELETE RESTRICT,
    operation_id UUID NOT NULL,
    entry_index INTEGER NOT NULL CHECK (entry_index >= 0),
    type VARCHAR(32) NOT NULL CHECK (type IN (
        'DAILY_REWARD',
        'STAKE_RESERVE',
        'STAKE_RELEASE',
        'STAKE_SETTLEMENT',
        'ADJUSTMENT'
    )),
    available_delta BIGINT NOT NULL,
    locked_delta BIGINT NOT NULL,
    challenge_id UUID NULL,
    reverses_entry_id UUID NULL REFERENCES coin_ledger_entries (id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_coin_ledger_operation_entry UNIQUE (operation_id, entry_index),
    CONSTRAINT ck_coin_ledger_delta_not_zero CHECK (available_delta <> 0 OR locked_delta <> 0)
);
CREATE INDEX idx_coin_ledger_user_created
    ON coin_ledger_entries (user_id, created_at, id);

CREATE FUNCTION reject_coin_ledger_entry_mutation()
    RETURNS trigger
    LANGUAGE plpgsql
AS 'BEGIN RAISE EXCEPTION ''coin_ledger_entries is append-only''; END;';

CREATE TRIGGER coin_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON coin_ledger_entries
    FOR EACH ROW
    EXECUTE FUNCTION reject_coin_ledger_entry_mutation();

-- Один claim на пользователя и календарную дату UTC.
CREATE TABLE daily_reward_claims (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES coin_wallets (user_id) ON DELETE RESTRICT,
    claim_date DATE NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    ledger_entry_id UUID NOT NULL UNIQUE REFERENCES coin_ledger_entries (id) ON DELETE RESTRICT,
    CONSTRAINT uq_daily_reward_claim_user_date UNIQUE (user_id, claim_date)
);
CREATE INDEX idx_daily_reward_claim_user_claimed
    ON daily_reward_claims (user_id, claimed_at DESC);

-- Снимок первого результата по Idempotency-Key; исходный ключ не хранится.
CREATE TABLE daily_reward_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES coin_wallets (user_id) ON DELETE RESTRICT,
    key_digest BYTEA NOT NULL CHECK (octet_length(key_digest) = 32),
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN ('GRANTED', 'ALREADY_CLAIMED')),
    claim_id UUID NOT NULL REFERENCES daily_reward_claims (id) ON DELETE RESTRICT,
    response_snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_daily_reward_request_user_key UNIQUE (user_id, key_digest)
);
CREATE INDEX idx_daily_reward_request_user_created
    ON daily_reward_requests (user_id, created_at);

COMMENT ON TABLE coin_ledger_entries IS 'Неизменяемый журнал операций Coins.';
COMMENT ON TABLE daily_reward_claims IS 'Получения ежедневной награды; дата и время в UTC.';
COMMENT ON TABLE daily_reward_requests IS 'Идемпотентные запросы ежедневной награды с сохранённым ответом.';
