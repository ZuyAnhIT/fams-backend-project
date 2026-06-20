-- Reference copy of role seed data.
-- Authoritative version: Flyway migration V13__seed_roles_and_permissions.sql
-- All system roles have tenant_id IS NULL and is_system = TRUE.

-- PLATFORM_ADMIN   — all permissions (platform + tenant)
-- TENANT_ADMIN     — all tenant permissions (no platform-only tenants/plans)
-- HR_MANAGER       — employees, attendance, violations, reports, shifts, assignments, notifications, audit
-- SITE_SUPERVISOR  — sites, checkins, randomchecks, violations, employees(r), shifts(r), attendance(r)
-- EMPLOYEE         — checkins:create/read, attendance:read, notifications:read/list
