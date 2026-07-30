-- Addresses feedback from the Web and App integration reports (2026-07-29):
--   1. Web: HR review screen cannot tell "not applicable" (gps_only) apart from
--      "still verifying" (null, async job pending) for Face ID/liveness results, and has no
--      way to see whether a record came from offline sync or who overrode it.
--   2. App: checkout currently re-resolves the effective policy LIVE from the site/shift
--      config at checkout time — if HR changes a site's policy mid-shift, an employee who
--      checked in under a weaker policy can get stuck at checkout unable to satisfy a policy
--      they were never told about. Snapshotting at check-in (same principle already applied to
--      shift-time fields in V72) fixes this without weakening buddy-checkout prevention, since
--      that protection comes from "checkout requires the same proof check-in required", not
--      from always using the latest policy value.

ALTER TABLE checkins
    ADD COLUMN effective_checkin_policy VARCHAR(20)
        CHECK (effective_checkin_policy IN ('gps_only', 'gps_face', 'gps_face_liveness')),
    ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'online'
        CHECK (source IN ('online', 'offline')),
    ADD COLUMN overridden_by UUID,
    ADD COLUMN overridden_at TIMESTAMPTZ;

-- Backfill source for existing rows: only OfflineSyncService has ever set client_nonce,
-- so its presence accurately identifies historically-offline records.
UPDATE checkins SET source = 'offline' WHERE client_nonce IS NOT NULL;

COMMENT ON COLUMN checkins.effective_checkin_policy IS
    'Snapshot of the resolved (site, with shift override) checkin_policy AT CHECK-IN TIME. '
    'NULL for records created before this column existed. Checkout enforcement uses this '
    'snapshot when present, falling back to a live resolve only for legacy NULL rows.';
COMMENT ON COLUMN checkins.source IS 'online (submitCheckin) | offline (OfflineSyncService)';
COMMENT ON COLUMN checkins.overridden_by IS 'users.id of the HR/admin who last called the override endpoint, if any.';
COMMENT ON COLUMN checkins.overridden_at IS 'Timestamp of the last override call, if any.';
