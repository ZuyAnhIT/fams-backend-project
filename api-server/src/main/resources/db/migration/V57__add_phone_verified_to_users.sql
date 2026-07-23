-- Issue #1 (docs/issues/ISSUES.md): phone-only registration previously activated the
-- account without ever verifying the phone via OTP. This column tracks whether the
-- phone number was actually proven via a verified Firebase phone-auth ID token.
ALTER TABLE users ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT FALSE;
