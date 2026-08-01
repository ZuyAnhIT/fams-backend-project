-- Links random-check outcomes into attendance/payroll visibility (never auto-deducts pay —
-- see docs/api/random-check-config-review.md §1): a day with >=1 failed/no_response random
-- check gets flagged on the attendance summary, mirroring how has_pending_review_session /
-- has_rejected_session already flag (rather than silently mutate) uncertain days.
ALTER TABLE attendance_summaries
    ADD COLUMN has_random_check_failure BOOLEAN NOT NULL DEFAULT false;

-- Per-tenant/site configurable point at which repeated random-check failures in a month are
-- surfaced as "exceeds threshold" on the HR monthly report — informational escalation signal
-- only, HR still makes the call via the existing /adjust endpoint.
ALTER TABLE random_check_configs
    ADD COLUMN failure_escalation_threshold INTEGER NOT NULL DEFAULT 3
        CHECK (failure_escalation_threshold >= 1);

-- Manual (HR-targeted) checks bypass the applicableRoles population filter by design — the point
-- of manually targeting one employee is investigating a specific concern, not general sampling.
-- Because that's an explicit override of policy scope, it gets its own audit fields (who, why) —
-- same principle as attendance's adjustmentReason.
ALTER TABLE scheduled_checks
    ADD COLUMN manual_reason TEXT,
    ADD COLUMN triggered_by UUID REFERENCES users(id);
