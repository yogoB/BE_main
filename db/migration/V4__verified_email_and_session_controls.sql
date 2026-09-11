ALTER TABLE app_user ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_user ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0;
UPDATE app_user SET email_verified=TRUE WHERE google_sub IS NOT NULL;

-- Retire previous one-hour sessions when deploying the stricter policy.
DELETE FROM auth_session;
ALTER TABLE auth_session ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE;
ALTER TABLE auth_session ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE auth_session ADD COLUMN last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE auth_session ADD COLUMN user_agent VARCHAR(200) NOT NULL DEFAULT 'Unknown';

CREATE TABLE auth_email_token (
    token_hash CHAR(64) PRIMARY KEY,
    purpose VARCHAR(10) NOT NULL CHECK (purpose IN ('SIGNUP', 'RESET')),
    email TEXT NOT NULL,
    user_id BIGINT REFERENCES app_user(id) ON DELETE CASCADE,
    credential_version BIGINT,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX auth_email_token_email ON auth_email_token(email);
