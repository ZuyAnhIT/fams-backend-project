-- Fixes 2 business-logic bugs found in an audit of the attendance summary module
-- (2026-07-31, see docs/api/attendance-management-api.md):
--
-- 1. AttendanceSummaryService.recompute() aggregated ALL checkin sessions regardless of
--    status — a 'pending_review' (unconfirmed, e.g. GPS/face-verify escalation) or 'rejected'
--    (HR-confirmed invalid, e.g. buddy check-in) session counted identically to a 'valid' one
--    toward work minutes, late/early/OT detection, and missing-checkout flags. This directly
--    contradicted the product's own promise to employees at checkout time ("This won't affect
--    your pay until reviewed" — CheckinService.resolveDisplayMessage). Fixed in application
--    code to only aggregate 'valid' sessions; these 2 new columns make that filtering visible
--    on the summary row itself instead of silently dropping affected days to zero with no
--    explanation.
-- 2. HR's manual adjustment (POST .../adjust) could be silently overwritten by any later
--    automatic recompute (a late offline-sync upload landing on that date, or a manual
--    /recompute call) with no warning — fixed in application code by having recompute() skip
--    any summary row that already has a non-null adjustment_reason.

ALTER TABLE attendance_summaries
    ADD COLUMN has_pending_review_session BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN has_rejected_session BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN attendance_summaries.has_pending_review_session IS
    'True if at least one checkin session this day is status=pending_review — the computed '
    'fields (work minutes, late/early/OT, missing_checkout) exclude that session''s '
    'contribution until HR resolves it, so this day''s numbers may be provisional/incomplete.';
COMMENT ON COLUMN attendance_summaries.has_rejected_session IS
    'True if at least one checkin session this day was status=rejected by HR — excluded from '
    'every computed field on this row (confirmed invalid, never counted).';
