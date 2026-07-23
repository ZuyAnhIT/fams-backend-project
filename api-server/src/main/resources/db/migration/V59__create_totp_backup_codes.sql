-- Issue #5 (docs/issues/ISSUES.md): 2FA had no recovery path — losing the authenticator
-- device meant permanent lockout (disabling 2FA itself required being logged in, which
-- you can't do without the second factor). One-time backup codes close that gap.
CREATE TABLE totp_backup_codes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash VARCHAR(255) NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_totp_backup_codes_user_id ON totp_backup_codes(user_id);
