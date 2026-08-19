-- #144 (2026-08-19): "Dọn dữ liệu ảnh và notification cũ" — DataRetentionJob previously only
-- read a single global @Value config, no per-tenant override existed anywhere. NULL means "use
-- the global default" (app.data-retention.notification-days / biometric-photo-days), matching the
-- same null-means-unspecified convention already used elsewhere (e.g. plan_limits columns).
ALTER TABLE tenant_settings
    ADD COLUMN data_retention_days INT;
