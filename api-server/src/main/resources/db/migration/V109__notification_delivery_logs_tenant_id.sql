-- #144 (2026-08-19 follow-up): notification_delivery_logs had no tenant_id, so per-tenant data
-- retention couldn't cover it (only notifications/biometric photos got tenant-scoped sweeps).
-- Nullable — many rows (push-only paths, platform-admin ops alerts) genuinely have no tenant.
-- Backfilled from notifications.tenant_id where a notification_id link exists; rows with no
-- notification_id (or an already-deleted notification) stay NULL, same as before this migration.
ALTER TABLE notification_delivery_logs
    ADD COLUMN tenant_id UUID;

UPDATE notification_delivery_logs dl
SET tenant_id = n.tenant_id
FROM notifications n
WHERE dl.notification_id = n.id
  AND dl.tenant_id IS NULL;

CREATE INDEX idx_notification_delivery_logs_tenant_id ON notification_delivery_logs (tenant_id);
