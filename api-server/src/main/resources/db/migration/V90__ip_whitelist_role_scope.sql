-- #19 IP whitelist: replace the unenforceable client-type "scope" (web_admin/api/all — the
-- backend cannot reliably tell which client type made a request) with a role-based scope.
-- Empty role set on an entry = applies to every role (matches the old "all" behavior).
-- A non-empty role set = that entry only restricts users holding one of those roles, so a
-- tenant can whitelist office IPs for back-office roles (Company Admin/HR) while leaving
-- field roles (Site Supervisor/Field Employee) free to check in from anywhere.

CREATE TABLE tenant_ip_whitelist_roles (
    ip_whitelist_id UUID NOT NULL REFERENCES tenant_ip_whitelists(id) ON DELETE CASCADE,
    role_name VARCHAR(100) NOT NULL,
    PRIMARY KEY (ip_whitelist_id, role_name)
);

ALTER TABLE tenant_ip_whitelists DROP COLUMN scope;
