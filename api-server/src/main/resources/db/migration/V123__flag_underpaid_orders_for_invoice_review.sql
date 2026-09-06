ALTER TABLE billing_orders
    DROP CONSTRAINT ck_billing_orders_invoice_status;

ALTER TABLE billing_orders
    ADD CONSTRAINT ck_billing_orders_invoice_status CHECK (invoice_status IN (
        'NOT_ELIGIBLE', 'PAYMENT_REVIEW', 'PENDING_ISSUANCE', 'ISSUED', 'FAILED'
    ));

UPDATE billing_orders
SET invoice_status = 'PAYMENT_REVIEW'
WHERE status = 'UNDERPAID'
  AND amount_paid > 0
  AND invoice_status = 'NOT_ELIGIBLE';
