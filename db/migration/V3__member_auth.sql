-- Google-only members do not have a local password. Email is not an OAuth identity.
ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE app_user ADD COLUMN google_sub VARCHAR(255) UNIQUE;
ALTER TABLE app_user ADD CONSTRAINT app_user_credential CHECK (password_hash IS NOT NULL OR google_sub IS NOT NULL);
CREATE UNIQUE INDEX app_user_email_normalized ON app_user(lower(btrim(email)));

-- Only fingerprints are persisted: a DB read or JWT signing key alone cannot mint a session.
CREATE TABLE auth_session (
    token_hash CHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    binding_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX auth_session_user ON auth_session(user_id);

CREATE TABLE auth_rate_limit (
    bucket CHAR(64) PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL
);
