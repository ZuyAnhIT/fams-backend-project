-- Backend readiness assessment (docs/reviews/backend/backend-readiness-assessment-2026-08-12.md):
-- fix 2 real cross-module consistency gaps found by tracing the attendance and random-check
-- lifecycles end-to-end (not new features, existing endpoints behaving inconsistently).

-- Gap #1: if the async face/liveness AI-verification callback for a check_response never
-- arrives (fams-ai down/timeout/crash), the response stays outcome='pass' with face_verified
-- IS NULL forever — no job ever revisits it. Allow a new violation_type so the reconciliation
-- job (FaceVerifyTimeoutService) can flag these distinctly from a genuine face/liveness fail,
-- so HR can tell "AI service was down" apart from "employee actually failed verification".
ALTER TABLE violations DROP CONSTRAINT violations_violation_type_check;
ALTER TABLE violations ADD CONSTRAINT violations_violation_type_check
    CHECK (violation_type IN ('no_response', 'location_fail', 'face_fail', 'liveness_fail', 'face_verify_timeout'));

-- Gap #2: ViolationService.updateAttendanceImpact() sets affects_attendance but
-- AttendanceSummaryService's hasRandomCheckFailure computation never read it — only
-- violation.resolution (dismissed or not). affects_attendance defaults FALSE for every new
-- violation (V records "not yet reviewed", not "reviewed as not counting"), so it cannot be
-- used directly as an override without this extra column to distinguish "never touched" from
-- "HR explicitly reviewed and chose false". Once reviewed, affects_attendance becomes the
-- authoritative signal (overriding the resolution-based default) — same semantics as the
-- Task 118 fix note already committed in ViolationService, now made actually load-bearing.
ALTER TABLE violations ADD COLUMN attendance_impact_reviewed BOOLEAN NOT NULL DEFAULT FALSE;
