-- Учётные записи Identity: email, состояние доступа и технические даты.
CREATE TABLE users (
    id UUID PRIMARY KEY,
    email_canonical VARCHAR(254) NOT NULL,
    email_version BIGINT NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT users_email_canonical_format CHECK (
        char_length(email_canonical) BETWEEN 3 AND 254
        AND email_canonical ~ '^[!-~]+$'
        AND email_canonical = lower(email_canonical)
    ),
    CONSTRAINT users_email_version_positive CHECK (email_version > 0),
    CONSTRAINT users_status_allowed CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'DISABLED'))
);

COMMENT ON TABLE users IS 'Учётные записи Identity: canonical email и состояние доступа.';

CREATE UNIQUE INDEX users_email_canonical_unique ON users (email_canonical);

-- Хеши паролей храним отдельно от учётной записи; открытого пароля здесь нет.
CREATE TABLE user_credentials (
    user_id UUID PRIMARY KEY REFERENCES users (id),
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE user_credentials IS 'Стойкие хеши паролей пользователей Identity.';

-- Справочник системных ролей; его значения создаются миграцией, а не клиентом.
CREATE TABLE roles (
    id SMALLINT PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    CONSTRAINT roles_code_allowed CHECK (code IN ('USER', 'MODERATOR', 'ADMIN', 'SUPER_ADMIN'))
);

INSERT INTO roles (id, code)
VALUES (1, 'USER'), (2, 'MODERATOR'), (3, 'ADMIN'), (4, 'SUPER_ADMIN');

COMMENT ON TABLE roles IS 'Системный справочник ролей Identity.';

-- Связь пользователя с одной или несколькими системными ролями.
CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users (id),
    role_id SMALLINT NOT NULL REFERENCES roles (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_id)
);

COMMENT ON TABLE user_roles IS 'Назначенные пользователю системные роли.';

-- Долговечные идемпотентные операции регистрации и данные worker для повторов.
CREATE TABLE registration_operations (
    id UUID PRIMARY KEY,
    idempotency_key_digest BYTEA NOT NULL,
    user_id UUID NOT NULL UNIQUE REFERENCES users (id),
    status VARCHAR(16) NOT NULL,
    completed_at TIMESTAMPTZ NULL,
    profile_ready BOOLEAN NOT NULL DEFAULT FALSE,
    wallet_ready BOOLEAN NOT NULL DEFAULT FALSE,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retry_count INTEGER NOT NULL DEFAULT 0,
    lease_owner UUID NULL,
    lease_until TIMESTAMPTZ NULL,
    lease_version BIGINT NOT NULL DEFAULT 0,
    recovery_blocked_at TIMESTAMPTZ NULL,
    last_error_code VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT registration_operations_digest_sha256 CHECK (octet_length(idempotency_key_digest) = 32),
    CONSTRAINT registration_operations_status_allowed CHECK (status IN ('PENDING', 'COMPLETED')),
    CONSTRAINT registration_operations_completion_consistent CHECK (
        (status = 'PENDING' AND completed_at IS NULL)
        OR (
            status = 'COMPLETED'
            AND completed_at IS NOT NULL
            AND profile_ready
            AND wallet_ready
        )
    ),
    CONSTRAINT registration_operations_retry_count_non_negative CHECK (retry_count >= 0),
    CONSTRAINT registration_operations_lease_version_non_negative CHECK (lease_version >= 0),
    CONSTRAINT registration_operations_version_positive CHECK (version > 0),
    CONSTRAINT registration_operations_lease_consistent CHECK (
        (lease_owner IS NULL AND lease_until IS NULL)
        OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)
    )
);

COMMENT ON TABLE registration_operations IS 'Идемпотентные операции регистрации и их состояние восстановления.';

CREATE UNIQUE INDEX registration_operations_idempotency_key_digest_unique
    ON registration_operations (idempotency_key_digest);
CREATE INDEX registration_operations_scheduler_index
    ON registration_operations (next_retry_at, id)
    WHERE status = 'PENDING' AND recovery_blocked_at IS NULL;

-- Сессии устройств; access token не хранится, а отзыв фиксируется временем.
CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    registration_operation_id UUID NULL REFERENCES registration_operations (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NULL,
    CONSTRAINT auth_sessions_expires_after_created CHECK (expires_at > created_at),
    CONSTRAINT auth_sessions_revoked_after_created CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

COMMENT ON TABLE auth_sessions IS 'Сессии пользователей Identity, включая сессии после регистрации.';

CREATE INDEX auth_sessions_user_id_index ON auth_sessions (user_id);
CREATE UNIQUE INDEX auth_sessions_active_registration_operation_unique
    ON auth_sessions (registration_operation_id)
    WHERE registration_operation_id IS NOT NULL AND revoked_at IS NULL;

-- Одноразовые refresh token: хранится только SHA-256 хеш их случайного секрета.
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES auth_sessions (id),
    secret_hash BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ NULL,
    revoked_at TIMESTAMPTZ NULL,
    replaced_by_token_id UUID NULL UNIQUE REFERENCES refresh_tokens (id),
    CONSTRAINT refresh_tokens_secret_hash_sha256 CHECK (octet_length(secret_hash) = 32),
    CONSTRAINT refresh_tokens_expires_after_created CHECK (expires_at > created_at),
    CONSTRAINT refresh_tokens_used_after_created CHECK (used_at IS NULL OR used_at >= created_at),
    CONSTRAINT refresh_tokens_revoked_after_created CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

COMMENT ON TABLE refresh_tokens IS 'Хеши одноразовых refresh token и история их ротации.';

CREATE UNIQUE INDEX refresh_tokens_secret_hash_unique ON refresh_tokens (secret_hash);
CREATE INDEX refresh_tokens_session_id_index ON refresh_tokens (session_id);
CREATE INDEX refresh_tokens_expires_at_index ON refresh_tokens (expires_at);
