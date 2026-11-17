-- Lease для recovery worker: строки забираются через FOR UPDATE SKIP LOCKED,
-- аренда продлевается на поколение и не освобождает hold по таймеру.

ALTER TABLE challenge_operations
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN lease_generation BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_challenge_operations_lease_generation CHECK (lease_generation >= 0);

ALTER TABLE challenge_release_intents
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN lease_generation BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_challenge_release_intents_lease_generation CHECK (lease_generation >= 0);

ALTER TABLE challenge_stake_sets
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN lease_generation BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_challenge_stake_sets_lease_generation CHECK (lease_generation >= 0);

CREATE INDEX ix_challenge_operations_recovery
    ON challenge_operations (created_at)
    WHERE status IN ('PENDING', 'RESERVED');

CREATE INDEX ix_challenge_release_intents_pending
    ON challenge_release_intents (created_at)
    WHERE status = 'PENDING';
