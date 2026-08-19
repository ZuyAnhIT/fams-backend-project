-- #138 (2026-08-19 follow-up): AC calls for "show metadata endpoint/status nếu có" on the
-- request_id trace view — entity had no columns for either. endpoint is captured synchronously
-- inside AuditLogService.record() (the request path is known at record time). http_status is
-- NOT known at record time (the controller hasn't returned yet) — it's backfilled best-effort by
-- RequestIdFilter after the response completes, correlated by request_id. Both nullable: many
-- audit rows are written outside a real HTTP request (scheduled jobs), and http_status also stays
-- null if the backfill update itself fails (never blocks the real response).
ALTER TABLE audit_logs
    ADD COLUMN endpoint VARCHAR(500),
    ADD COLUMN http_status INT;
