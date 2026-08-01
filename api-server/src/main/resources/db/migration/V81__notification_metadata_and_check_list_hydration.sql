-- Structured, machine-readable payload alongside the existing title/body — found missing via FE
-- audit (2026-07-31): RANDOM_CHECK_SENT notifications carried no checkId, forcing the app to fall
-- back to opening a generic list instead of deep-linking straight to the check. Nullable/JSONB so
-- every other existing notification type is completely unaffected.
ALTER TABLE notifications
    ADD COLUMN metadata JSONB;
