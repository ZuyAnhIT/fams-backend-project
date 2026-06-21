ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- Existing users (seeded before verification was introduced) are considered verified
UPDATE users SET email_verified = TRUE WHERE deleted_at IS NULL;
