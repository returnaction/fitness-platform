-- Challenge владеет соревнованиями, участием, ставками и результатом.
-- Межсервисных FK нет: creator_user_id и user_id принадлежат Identity.
-- Денежные значения хранятся в BIGINT, digest — BYTEA длиной 32 байта.

CREATE TABLE challenges (
    id UUID PRIMARY KEY,
    creator_user_id UUID NOT NULL,
    title TEXT,
    description TEXT,
    mode VARCHAR(32),
    status VARCHAR(24) NOT NULL,
    stake_amount BIGINT,
    min_participants INTEGER,
    max_participants INTEGER,
    visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    start_mode VARCHAR(16),
    started_at TIMESTAMPTZ,
    duration_days INTEGER,
    next_join_sequence BIGINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_challenges_status CHECK (
        status IN ('DRAFT', 'OPEN', 'ACTIVE', 'AWAITING_RESULTS', 'FINISHED', 'CANCELLED')),
    CONSTRAINT chk_challenges_title CHECK (title IS NULL OR char_length(title) BETWEEN 1 AND 120),
    CONSTRAINT chk_challenges_description CHECK (description IS NULL OR char_length(description) <= 2000),
    CONSTRAINT chk_challenges_mode CHECK (mode IS NULL OR mode = 'HEAD_TO_HEAD'),
    CONSTRAINT chk_challenges_visibility CHECK (visibility = 'PUBLIC'),
    CONSTRAINT chk_challenges_start_mode CHECK (start_mode IS NULL OR start_mode = 'WHEN_FULL'),
    CONSTRAINT chk_challenges_stake CHECK (stake_amount IS NULL OR stake_amount BETWEEN 1 AND 1000000),
    CONSTRAINT chk_challenges_duration CHECK (duration_days IS NULL OR duration_days BETWEEN 1 AND 30),
    CONSTRAINT chk_challenges_participant_limits CHECK (
        (min_participants IS NULL OR min_participants > 0)
        AND (max_participants IS NULL OR max_participants > 0)
        AND (min_participants IS NULL OR max_participants IS NULL OR max_participants >= min_participants)),
    CONSTRAINT chk_challenges_next_join_sequence CHECK (next_join_sequence > 0),
    CONSTRAINT chk_challenges_version CHECK (version > 0),
    CONSTRAINT chk_challenges_published CHECK (
        status = 'DRAFT' OR (
            title IS NOT NULL
            AND mode = 'HEAD_TO_HEAD'
            AND visibility = 'PUBLIC'
            AND start_mode = 'WHEN_FULL'
            AND stake_amount IS NOT NULL
            AND min_participants = 2
            AND max_participants = 2
            AND duration_days IS NOT NULL)),
    CONSTRAINT chk_challenges_started CHECK (status NOT IN ('ACTIVE', 'FINISHED') OR started_at IS NOT NULL)
);

COMMENT ON TABLE challenges IS 'Дуэльные challenge MVP и состояние их жизненного цикла.';

CREATE TABLE challenge_participants (
    id UUID PRIMARY KEY,
    challenge_id UUID NOT NULL REFERENCES challenges (id),
    user_id UUID NOT NULL,
    status VARCHAR(24) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    join_sequence BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_challenge_participants_user UNIQUE (challenge_id, user_id),
    CONSTRAINT uq_challenge_participants_sequence UNIQUE (challenge_id, join_sequence),
    CONSTRAINT chk_challenge_participants_status CHECK (status IN ('JOINED', 'ACTIVE', 'WINNER', 'COMPLETED')),
    CONSTRAINT chk_challenge_participants_sequence CHECK (join_sequence > 0),
    CONSTRAINT chk_challenge_participants_version CHECK (version > 0)
);

COMMENT ON TABLE challenge_participants IS 'Подтверждённые участники challenge с порядковым номером вступления.';

CREATE TABLE challenge_operations (
    id UUID PRIMARY KEY,
    challenge_id UUID NOT NULL REFERENCES challenges (id),
    user_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    hold_id UUID,
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_challenge_operations_kind CHECK (kind IN ('PUBLISH', 'JOIN')),
    CONSTRAINT chk_challenge_operations_status CHECK (
        status IN ('PENDING', 'RESERVED', 'CONFIRMED', 'FAILED', 'RELEASING')),
    CONSTRAINT chk_challenge_operations_failure CHECK (failure_code IS NULL OR status = 'FAILED')
);

-- Незавершённая операция одна на пару (challenge, user); завершённые не блокируют.
CREATE UNIQUE INDEX ux_challenge_operations_active
    ON challenge_operations (challenge_id, user_id)
    WHERE status IN ('PENDING', 'RESERVED', 'RELEASING');

CREATE INDEX ix_challenge_operations_challenge_status
    ON challenge_operations (challenge_id, status);

COMMENT ON TABLE challenge_operations IS 'Durable-операции PUBLISH и JOIN, переживающие потерю ответа Coins.';

CREATE TABLE challenge_stake_sets (
    challenge_id UUID PRIMARY KEY REFERENCES challenges (id),
    version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    seal_operation_id UUID UNIQUE,
    digest BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_challenge_stake_sets_version UNIQUE (challenge_id, version),
    CONSTRAINT chk_challenge_stake_sets_version CHECK (version > 0),
    CONSTRAINT chk_challenge_stake_sets_state CHECK (state IN ('COLLECTING', 'DRAINING', 'SEALED')),
    CONSTRAINT chk_challenge_stake_sets_digest CHECK (digest IS NULL OR octet_length(digest) = 32),
    CONSTRAINT chk_challenge_stake_sets_sealed CHECK (
        state <> 'SEALED' OR (seal_operation_id IS NOT NULL AND digest IS NOT NULL))
);

COMMENT ON TABLE challenge_stake_sets IS 'Барьер набора ставок: DRAINING запрещает join, SEALED подтверждён Coins.';

CREATE TABLE challenge_stake_set_items (
    challenge_id UUID NOT NULL,
    set_version BIGINT NOT NULL,
    hold_id UUID NOT NULL,
    user_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    PRIMARY KEY (challenge_id, set_version, hold_id),
    CONSTRAINT uq_challenge_stake_set_items_user UNIQUE (challenge_id, set_version, user_id),
    CONSTRAINT fk_challenge_stake_set_items_set FOREIGN KEY (challenge_id, set_version)
        REFERENCES challenge_stake_sets (challenge_id, version),
    CONSTRAINT chk_challenge_stake_set_items_version CHECK (set_version > 0),
    CONSTRAINT chk_challenge_stake_set_items_amount CHECK (amount > 0)
);

COMMENT ON TABLE challenge_stake_set_items IS 'Снимок подтверждённых hold для повторов одной seal-команды.';

CREATE TABLE challenge_release_intents (
    id UUID PRIMARY KEY,
    challenge_id UUID NOT NULL REFERENCES challenges (id),
    hold_id UUID NOT NULL,
    reason VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_challenge_release_intents_hold UNIQUE (hold_id),
    CONSTRAINT chk_challenge_release_intents_reason CHECK (
        reason IN ('JOIN_NOT_CONFIRMED', 'CHALLENGE_CANCELLED')),
    CONSTRAINT chk_challenge_release_intents_status CHECK (status IN ('PENDING', 'CONFIRMED'))
);

CREATE INDEX ix_challenge_release_intents_challenge_status
    ON challenge_release_intents (challenge_id, status);

COMMENT ON TABLE challenge_release_intents IS 'Один стабильный release operation ID на hold; причина не меняется при повторе.';

CREATE TABLE challenge_results (
    id UUID PRIMARY KEY,
    challenge_id UUID NOT NULL REFERENCES challenges (id),
    set_version BIGINT NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    rule_version VARCHAR(64) NOT NULL,
    winner_count BIGINT NOT NULL,
    winners_digest BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_challenge_results_challenge UNIQUE (challenge_id),
    CONSTRAINT fk_challenge_results_set FOREIGN KEY (challenge_id, set_version)
        REFERENCES challenge_stake_sets (challenge_id, version),
    CONSTRAINT chk_challenge_results_set_version CHECK (set_version > 0),
    CONSTRAINT chk_challenge_results_outcome CHECK (
        outcome IN ('SINGLE_WINNER', 'MULTIPLE_WINNERS', 'DRAW')),
    CONSTRAINT chk_challenge_results_rule_version CHECK (char_length(rule_version) BETWEEN 1 AND 64),
    CONSTRAINT chk_challenge_results_winner_count CHECK (winner_count > 0),
    CONSTRAINT chk_challenge_results_digest CHECK (octet_length(winners_digest) = 32)
);

COMMENT ON TABLE challenge_results IS 'Единственный окончательный результат challenge; победители хранятся отдельными строками.';

CREATE TABLE challenge_result_winners (
    result_id UUID NOT NULL REFERENCES challenge_results (id),
    user_id UUID NOT NULL,
    PRIMARY KEY (result_id, user_id)
);

COMMENT ON TABLE challenge_result_winners IS 'Неизменяемый список получателей долей результата.';

CREATE TABLE challenge_settlement_status (
    challenge_id UUID PRIMARY KEY REFERENCES challenges (id),
    result_id UUID NOT NULL REFERENCES challenge_results (id),
    settlement_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    source_event_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_challenge_settlement_status_result UNIQUE (result_id),
    CONSTRAINT uq_challenge_settlement_status_settlement UNIQUE (settlement_id),
    CONSTRAINT uq_challenge_settlement_status_event UNIQUE (source_event_id),
    CONSTRAINT chk_challenge_settlement_status_state CHECK (state = 'COMPLETED')
);

COMMENT ON TABLE challenge_settlement_status IS 'Проекция подтверждённого расчёта Coins; не является ledger.';

CREATE TABLE challenge_outbox (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    schema_version INTEGER NOT NULL,
    aggregate_id UUID NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    lease_generation BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_challenge_outbox_schema_version CHECK (schema_version > 0),
    CONSTRAINT chk_challenge_outbox_lease_generation CHECK (lease_generation >= 0)
);

CREATE INDEX ix_challenge_outbox_unpublished
    ON challenge_outbox (created_at)
    WHERE published_at IS NULL;

COMMENT ON TABLE challenge_outbox IS 'Событие Challenge и локальное изменение фиксируются одним commit.';

CREATE TABLE challenge_inbox (
    consumer VARCHAR(96) NOT NULL,
    event_id UUID NOT NULL,
    payload_digest BYTEA NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer, event_id),
    CONSTRAINT chk_challenge_inbox_digest CHECK (octet_length(payload_digest) = 32)
);

COMMENT ON TABLE challenge_inbox IS 'Идемпотентная обработка событий; тот же event_id с другим digest — карантин.';

CREATE TABLE challenge_idempotency_records (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    method VARCHAR(8) NOT NULL,
    path TEXT NOT NULL,
    key UUID NOT NULL,
    request_fingerprint BYTEA NOT NULL,
    response_status INTEGER NOT NULL,
    response_body JSONB NOT NULL,
    response_headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_challenge_idempotency UNIQUE (user_id, method, path, key),
    CONSTRAINT chk_challenge_idempotency_fingerprint CHECK (octet_length(request_fingerprint) = 32),
    CONSTRAINT chk_challenge_idempotency_status CHECK (response_status BETWEEN 100 AND 599)
);

CREATE INDEX ix_challenge_idempotency_created
    ON challenge_idempotency_records (created_at);

COMMENT ON TABLE challenge_idempotency_records IS 'Сохранённые HTTP-ответы по Idempotency-Key; ключ не логируется.';
