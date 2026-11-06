-- Профиль принадлежит Accounts; user_id ссылается на Identity без межбазового FK.
CREATE TABLE user_profiles (
    user_id UUID PRIMARY KEY,
    public_profile_id UUID NOT NULL UNIQUE,
    nickname VARCHAR(30) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    date_of_birth DATE,
    timezone VARCHAR(64) NOT NULL DEFAULT 'UTC',
    bio VARCHAR(500),
    visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_profiles_visibility_check CHECK (visibility IN ('PRIVATE', 'PUBLIC')),
    CONSTRAINT user_profiles_version_check CHECK (version > 0),
    CONSTRAINT user_profiles_public_id_check CHECK (public_profile_id <> user_id)
);

-- Ник уникален независимо от регистра.
CREATE UNIQUE INDEX user_profiles_nickname_unique ON user_profiles (lower(nickname));
COMMENT ON TABLE user_profiles IS 'Профили пользователей, принадлежащие Accounts.';
