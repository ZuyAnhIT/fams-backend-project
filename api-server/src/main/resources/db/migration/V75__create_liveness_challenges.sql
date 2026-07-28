-- Active liveness (challenge-response): client is asked to perform a randomized sequence of
-- actions (center/turn_left/turn_right/look_up/look_down/blink) in front of the camera, proven
-- via head-pose estimation (OpenCV solvePnP) + eye-aspect-ratio blink detection on the submitted
-- frame sequence — not just single-frame passive anti-spoofing (MiniFASNet, still run too, on
-- the 'center' frame, as defense-in-depth). See docs/api/face-id-management-api.md.
--
-- A challenge is single-use: 'enroll' challenges get consumed by POST /face-id/enroll (replaces
-- submitting raw photos directly); 'checkin' challenges get consumed by POST /checkin at a
-- Face-ID-required site (replaces submitting a raw employeePhotoBase64).
CREATE TABLE liveness_challenges (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    employee_id     UUID NOT NULL REFERENCES employees(id),
    purpose         VARCHAR(20) NOT NULL CHECK (purpose IN ('enroll', 'checkin')),
    actions         TEXT[] NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending', 'passed', 'failed', 'expired', 'consumed')),
    result_detail   JSONB,
    center_frame_path TEXT,
    -- Computed once the challenge passes (averaged across accepted frames); consumed by
    -- enroll/checkin afterward. Intentionally NOT mapped in the Java entity — same convention
    -- as face_profiles.embedding: only fams-ai ever reads/writes raw biometric vectors.
    embedding       DOUBLE PRECISION[],
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    completed_at    TIMESTAMPTZ,
    consumed_at     TIMESTAMPTZ
);

CREATE INDEX idx_liveness_challenges_tenant_employee ON liveness_challenges(tenant_id, employee_id);
CREATE INDEX idx_liveness_challenges_status ON liveness_challenges(status);
