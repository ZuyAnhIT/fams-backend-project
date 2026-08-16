-- 2026-08-16 gap fix: manual employee creation ("Thêm hồ sơ chưa cần đăng nhập") has no login
-- account yet, so no real UserRole can be granted there — but HR still wants to record WHICH
-- role this person is intended to get once invited, instead of that intent being lost and the
-- eventual invite silently defaulting to EMPLOYEE. Carried forward automatically by
-- EmployeeInvitationService#sendInvitation when inviting the same email with no explicit roleId.
ALTER TABLE employees
    ADD COLUMN planned_role_id UUID NULL;
