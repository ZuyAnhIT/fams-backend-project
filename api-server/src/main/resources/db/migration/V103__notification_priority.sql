-- #89 (2026-08-17): backend-driven priority so notifications aren't just visually differentiated
-- by a FE-hardcoded per-eventType color map that can't tell "urgent random check about to expire"
-- apart from "role assigned" if a future eventType needs a different priority than its category
-- default. Default 'normal' so existing rows and any not-yet-cataloged eventType stay sane.
ALTER TABLE notifications
    ADD COLUMN priority VARCHAR(20) NOT NULL DEFAULT 'normal';

ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_priority CHECK (priority IN ('low', 'normal', 'high', 'critical'));
