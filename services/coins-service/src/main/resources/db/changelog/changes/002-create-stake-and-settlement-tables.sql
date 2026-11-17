-- Ставки и расчёт Coins для Spec 020. Существующая coin_wallets не изменяется.
-- Межсервисных FK нет: challenge_id и result_id принадлежат Challenge.

CREATE TABLE coin_holds (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES coin_wallets (user_id) ON DELETE RESTRICT,
    challenge_id UUID NOT NULL,
    join_operation_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_coin_holds_join_operation UNIQUE (join_operation_id),
    CONSTRAINT chk_coin_holds_amount CHECK (amount > 0),
    CONSTRAINT chk_coin_holds_status CHECK (status IN ('ACTIVE', 'RELEASED', 'SETTLED'))
);

-- Два активных резерва одного пользователя в одном challenge запрещены.
CREATE UNIQUE INDEX ux_coin_holds_active
    ON coin_holds (challenge_id, user_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_coin_holds_challenge_status
    ON coin_holds (challenge_id, status);

COMMENT ON TABLE coin_holds IS 'Резервы ставок; окончательное состояние меняется только Coins.';

CREATE TABLE coin_ledger_entries (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES coin_wallets (user_id) ON DELETE RESTRICT,
    operation_id UUID NOT NULL,
    entry_index INTEGER NOT NULL,
    type VARCHAR(32) NOT NULL,
    available_delta BIGINT NOT NULL,
    locked_delta BIGINT NOT NULL,
    challenge_id UUID,
    reverses_entry_id UUID REFERENCES coin_ledger_entries (id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_coin_ledger_operation_entry UNIQUE (operation_id, entry_index),
    CONSTRAINT chk_coin_ledger_entry_index CHECK (entry_index >= 0),
    CONSTRAINT chk_coin_ledger_type CHECK (type IN ('RESERVE', 'RELEASE', 'SETTLEMENT')),
    CONSTRAINT chk_coin_ledger_delta CHECK (available_delta <> 0 OR locked_delta <> 0)
);

CREATE INDEX ix_coin_ledger_user_created
    ON coin_ledger_entries (user_id, created_at, id);

COMMENT ON TABLE coin_ledger_entries IS 'Неизменяемые проводки резервов, возвратов и расчётов.';

CREATE TABLE coin_stake_sets (
    challenge_id UUID PRIMARY KEY,
    state VARCHAR(32) NOT NULL,
    cancellation_id UUID,
    set_version BIGINT,
    digest BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_coin_stake_sets_version UNIQUE (challenge_id, set_version),
    CONSTRAINT chk_coin_stake_sets_state CHECK (
        state IN ('OPEN', 'SEALED', 'CANCEL_DRAINING', 'SETTLED', 'CANCELLED')),
    CONSTRAINT chk_coin_stake_sets_version CHECK (set_version IS NULL OR set_version > 0),
    CONSTRAINT chk_coin_stake_sets_digest CHECK (digest IS NULL OR octet_length(digest) = 32),
    CONSTRAINT chk_coin_stake_sets_open CHECK (
        state <> 'OPEN' OR (set_version IS NULL AND digest IS NULL)),
    CONSTRAINT chk_coin_stake_sets_sealed CHECK (
        state NOT IN ('SEALED', 'SETTLED') OR (set_version IS NOT NULL AND digest IS NOT NULL)),
    CONSTRAINT chk_coin_stake_sets_cancellation CHECK (
        state NOT IN ('CANCEL_DRAINING', 'CANCELLED') OR cancellation_id IS NOT NULL)
);

COMMENT ON TABLE coin_stake_sets IS 'Строка сериализации reserve, seal, отмены и расчёта по challenge.';

CREATE TABLE coin_stake_operations (
    kind VARCHAR(24) NOT NULL,
    id UUID NOT NULL,
    challenge_id UUID NOT NULL,
    request_hash BYTEA NOT NULL,
    result JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (kind, id),
    CONSTRAINT chk_coin_stake_operations_kind CHECK (
        kind IN ('RESERVE', 'RELEASE', 'SEAL', 'FREEZE', 'COMPLETE_CANCELLATION')),
    CONSTRAINT chk_coin_stake_operations_hash CHECK (octet_length(request_hash) = 32)
);

COMMENT ON TABLE coin_stake_operations IS 'Сохранённый идемпотентный исход stake-команды, включая отказ.';

CREATE TABLE coin_stake_seal_uploads (
    seal_operation_id UUID PRIMARY KEY,
    challenge_id UUID NOT NULL,
    set_version BIGINT NOT NULL,
    expected_count BIGINT NOT NULL,
    expected_digest BYTEA NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_coin_stake_seal_uploads_version CHECK (set_version > 0),
    CONSTRAINT chk_coin_stake_seal_uploads_count CHECK (expected_count > 0),
    CONSTRAINT chk_coin_stake_seal_uploads_digest CHECK (octet_length(expected_digest) = 32),
    CONSTRAINT chk_coin_stake_seal_uploads_status CHECK (status IN ('UPLOADING', 'SEALED'))
);

CREATE UNIQUE INDEX ux_coin_stake_seal_uploads_active
    ON coin_stake_seal_uploads (challenge_id)
    WHERE status = 'UPLOADING';

CREATE TABLE coin_stake_seal_batches (
    seal_operation_id UUID NOT NULL,
    batch_number INTEGER NOT NULL,
    payload_digest BYTEA NOT NULL,
    item_count SMALLINT NOT NULL,
    PRIMARY KEY (seal_operation_id, batch_number),
    CONSTRAINT fk_coin_stake_seal_batches_upload FOREIGN KEY (seal_operation_id)
        REFERENCES coin_stake_seal_uploads (seal_operation_id) ON DELETE RESTRICT,
    CONSTRAINT chk_coin_stake_seal_batches_number CHECK (batch_number >= 0),
    CONSTRAINT chk_coin_stake_seal_batches_digest CHECK (octet_length(payload_digest) = 32),
    CONSTRAINT chk_coin_stake_seal_batches_count CHECK (item_count BETWEEN 1 AND 500)
);

CREATE TABLE coin_stake_seal_items (
    seal_operation_id UUID NOT NULL,
    batch_number INTEGER NOT NULL,
    hold_id UUID NOT NULL,
    user_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    PRIMARY KEY (seal_operation_id, hold_id),
    CONSTRAINT uq_coin_stake_seal_items_user UNIQUE (seal_operation_id, user_id),
    CONSTRAINT fk_coin_stake_seal_items_batch FOREIGN KEY (seal_operation_id, batch_number)
        REFERENCES coin_stake_seal_batches (seal_operation_id, batch_number) ON DELETE RESTRICT,
    CONSTRAINT chk_coin_stake_seal_items_amount CHECK (amount > 0)
);

COMMENT ON TABLE coin_stake_seal_items IS 'Строки пакетов seal до финализации; балансы не меняют.';

CREATE TABLE coin_sealed_stakes (
    challenge_id UUID NOT NULL,
    set_version BIGINT NOT NULL,
    hold_id UUID NOT NULL REFERENCES coin_holds (id) ON DELETE RESTRICT,
    user_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    PRIMARY KEY (challenge_id, set_version, hold_id),
    CONSTRAINT uq_coin_sealed_stakes_user UNIQUE (challenge_id, set_version, user_id),
    CONSTRAINT fk_coin_sealed_stakes_set FOREIGN KEY (challenge_id, set_version)
        REFERENCES coin_stake_sets (challenge_id, set_version) ON DELETE RESTRICT,
    CONSTRAINT chk_coin_sealed_stakes_amount CHECK (amount > 0)
);

COMMENT ON TABLE coin_sealed_stakes IS 'Неизменяемый проверенный снимок закрытого набора.';

CREATE TABLE coin_settlements (
    id UUID PRIMARY KEY,
    result_id UUID NOT NULL,
    challenge_id UUID NOT NULL,
    set_version BIGINT NOT NULL,
    rule_version VARCHAR(64) NOT NULL,
    pool BIGINT NOT NULL,
    phase VARCHAR(24) NOT NULL,
    winner_count BIGINT NOT NULL,
    winners_digest BYTEA NOT NULL,
    winners_cursor TEXT,
    winners_loaded BIGINT NOT NULL DEFAULT 0,
    random_seed BYTEA,
    blocked_from_phase VARCHAR(24),
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    lease_version BIGINT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_coin_settlements_result UNIQUE (result_id),
    CONSTRAINT uq_coin_settlements_challenge UNIQUE (challenge_id),
    CONSTRAINT chk_coin_settlements_set_version CHECK (set_version > 0),
    CONSTRAINT chk_coin_settlements_pool CHECK (pool > 0),
    CONSTRAINT chk_coin_settlements_phase CHECK (
        phase IN ('READING_WINNERS', 'PREPARING', 'COLLECTING', 'DISTRIBUTING', 'COMPLETED', 'BLOCKED')),
    CONSTRAINT chk_coin_settlements_winner_count CHECK (winner_count > 0),
    CONSTRAINT chk_coin_settlements_winners_digest CHECK (octet_length(winners_digest) = 32),
    CONSTRAINT chk_coin_settlements_winners_loaded CHECK (winners_loaded >= 0 AND winners_loaded <= winner_count),
    CONSTRAINT chk_coin_settlements_random_seed CHECK (random_seed IS NULL OR octet_length(random_seed) = 32),
    CONSTRAINT chk_coin_settlements_blocked_from CHECK (
        blocked_from_phase IS NULL
        OR blocked_from_phase IN ('READING_WINNERS', 'PREPARING', 'COLLECTING', 'DISTRIBUTING')),
    CONSTRAINT chk_coin_settlements_blocked_phase CHECK (
        (phase = 'BLOCKED' AND blocked_from_phase IS NOT NULL)
        OR (phase <> 'BLOCKED' AND blocked_from_phase IS NULL)),
    CONSTRAINT chk_coin_settlements_lease_pair CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT chk_coin_settlements_lease_version CHECK (lease_version >= 0),
    CONSTRAINT chk_coin_settlements_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_coin_settlements_version CHECK (version > 0),
    CONSTRAINT chk_coin_settlements_finished_lease CHECK (
        phase NOT IN ('COMPLETED', 'BLOCKED') OR (lease_owner IS NULL AND lease_until IS NULL))
);

CREATE INDEX ix_coin_settlements_next_attempt
    ON coin_settlements (next_attempt_at, id)
    WHERE phase IN ('READING_WINNERS', 'PREPARING', 'COLLECTING', 'DISTRIBUTING');

COMMENT ON TABLE coin_settlements IS 'Долговечная задача расчёта банка для одного результата challenge.';

CREATE TABLE coin_settlement_winners (
    settlement_id UUID NOT NULL REFERENCES coin_settlements (id) ON DELETE RESTRICT,
    user_id UUID NOT NULL,
    random_rank BYTEA,
    PRIMARY KEY (settlement_id, user_id),
    CONSTRAINT chk_coin_settlement_winners_rank CHECK (random_rank IS NULL OR octet_length(random_rank) = 32)
);

CREATE INDEX ix_coin_settlement_winners_rank
    ON coin_settlement_winners (settlement_id, random_rank, user_id);

CREATE TABLE coin_settlement_allocations (
    settlement_id UUID NOT NULL REFERENCES coin_settlements (id) ON DELETE RESTRICT,
    user_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    remainder_bonus SMALLINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    PRIMARY KEY (settlement_id, user_id),
    CONSTRAINT chk_coin_settlement_allocations_amount CHECK (amount >= 0),
    CONSTRAINT chk_coin_settlement_allocations_remainder CHECK (remainder_bonus IN (0, 1)),
    CONSTRAINT chk_coin_settlement_allocations_status CHECK (status IN ('PENDING', 'PAID'))
);

CREATE TABLE coin_settlement_hold_moves (
    settlement_id UUID NOT NULL REFERENCES coin_settlements (id) ON DELETE RESTRICT,
    hold_id UUID NOT NULL REFERENCES coin_holds (id) ON DELETE RESTRICT,
    amount BIGINT NOT NULL,
    PRIMARY KEY (settlement_id, hold_id),
    CONSTRAINT chk_coin_settlement_hold_moves_amount CHECK (amount > 0)
);

CREATE TABLE coin_inbox (
    consumer VARCHAR(96) NOT NULL,
    event_id UUID NOT NULL,
    payload_hash BYTEA NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer, event_id),
    CONSTRAINT chk_coin_inbox_hash CHECK (octet_length(payload_hash) = 32)
);

COMMENT ON TABLE coin_inbox IS 'Дедупликация событий с проверкой совпадения payload.';

CREATE TABLE coin_outbox (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    schema_version INTEGER NOT NULL,
    aggregate_id UUID NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    lease_generation BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_coin_outbox_schema_version CHECK (schema_version > 0),
    CONSTRAINT chk_coin_outbox_lease_generation CHECK (lease_generation >= 0)
);

CREATE INDEX ix_coin_outbox_unpublished
    ON coin_outbox (created_at)
    WHERE published_at IS NULL;

COMMENT ON TABLE coin_outbox IS 'Событие Coins и изменение состояния фиксируются одним commit.';
