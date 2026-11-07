-- Аудит действий оператора: кто, когда и почему возобновил регистрацию.
CREATE TABLE registration_recovery_audit (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES registration_operations (id),
    actor_user_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (char_length(btrim(reason)) > 0),
    previous_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX registration_recovery_audit_operation_index
    ON registration_recovery_audit (operation_id, created_at, id);
COMMENT ON TABLE registration_recovery_audit IS 'Аудит возобновления технически заблокированных регистраций.';
