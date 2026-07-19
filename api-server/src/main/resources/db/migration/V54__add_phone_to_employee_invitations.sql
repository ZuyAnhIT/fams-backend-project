-- Allow invitations to carry an optional phone number.
-- This enables HR to record the invitee's phone and, at acceptance time,
-- link the invitation email to an existing phone-based user account.

ALTER TABLE employee_invitations
    ADD COLUMN phone VARCHAR(20);

CREATE INDEX idx_emp_inv_phone ON employee_invitations(phone)
    WHERE phone IS NOT NULL AND deleted_at IS NULL;
