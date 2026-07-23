-- Issue #6 (docs/issues/ISSUES.md): there was no way to list active sessions/devices —
-- refresh_tokens had no display metadata (no last-used timestamp, no user-agent/IP) even
-- though device_id already existed as an opaque client-supplied string.
ALTER TABLE refresh_tokens
    ADD COLUMN last_used_at TIMESTAMPTZ,
    ADD COLUMN user_agent VARCHAR(500),
    ADD COLUMN ip_address VARCHAR(45);

UPDATE refresh_tokens SET last_used_at = created_at WHERE last_used_at IS NULL;
