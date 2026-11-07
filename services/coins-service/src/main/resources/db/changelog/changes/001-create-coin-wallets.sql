-- Кошелёк принадлежит Coins; user_id связан с Identity без внешнего ключа между базами.
CREATE TABLE coin_wallets (
    user_id UUID PRIMARY KEY,
    available_balance BIGINT NOT NULL DEFAULT 0 CHECK (available_balance >= 0),
    locked_balance BIGINT NOT NULL DEFAULT 0 CHECK (locked_balance >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE coin_wallets IS 'Кошельки пользователей: доступные и зарезервированные монеты.';
