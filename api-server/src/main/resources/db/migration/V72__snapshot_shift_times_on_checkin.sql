-- Payroll/audit correctness fix: previously, work-minutes and late/early/OT calculations
-- (CheckinService.computeWorkMinutes, AttendanceSummaryService.recompute) re-fetched the LIVE
-- Shift row every time they ran. AttendanceSummaryService.recompute() in particular can be
-- re-triggered for an arbitrarily old date (via HR's "override check-in" action), so editing a
-- Shift's startTime/endTime/allowOvernight/allowOvertime/lateCheckoutMinutes today would silently
-- reinterpret past attendance using the new hours the next time that day's summary was
-- recomputed. Snapshotting the shift's time-affecting fields onto each checkin at the moment it
-- is first resolved (check-in time) makes all later computations for that record immutable
-- against subsequent Shift edits, while leaving Shift itself freely editable for future use.

ALTER TABLE checkins
    ADD COLUMN shift_start_time TIME,
    ADD COLUMN shift_end_time TIME,
    ADD COLUMN shift_allow_overnight BOOLEAN,
    ADD COLUMN shift_allow_overtime BOOLEAN,
    ADD COLUMN shift_late_checkout_minutes INT;
