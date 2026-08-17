-- #88 (2026-08-17): FCM returns a provider messageId on successful send that was previously
-- discarded — needed for audit/traceability (matching a delivery log row back to the exact FCM
-- send event when debugging a delivery issue with Firebase support).
ALTER TABLE notification_delivery_logs
    ADD COLUMN provider_message_id VARCHAR(255);
