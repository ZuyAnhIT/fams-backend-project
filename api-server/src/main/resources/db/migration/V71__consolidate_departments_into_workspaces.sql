-- Consolidates the standalone `departments` concept into `workspaces` (type='department').
-- Departments (V55) and Workspaces (V20/V21) were two independent, non-cross-referencing org
-- structures — Department flat with no hierarchy, Workspace with a real parent/child tree and
-- membership roles. Product decision: keep a single org-chart mechanism (Workspace) and retire
-- the separate `departments` table. See docs/api/workspace-management-api.md section 4.
--
-- Row IDs are preserved 1:1 (department.id -> workspace.id) so that employees.department_id
-- keeps pointing at valid rows without any remapping — it now targets `workspaces` instead of
-- `departments`. `employees.department` (the cached display string) is left untouched: it was
-- already populated from the department name at assignment time and remains a valid cached
-- label for the migrated workspace of the same name.

-- Migrate every department (including soft-deleted ones, to preserve FK integrity for any
-- employee that still references one) into workspaces, skipping name collisions with an
-- existing active workspace in the same tenant (none exist at migration time in this deployment,
-- but the guard keeps this migration safe to reuse against a differently-seeded environment).
INSERT INTO workspaces (id, tenant_id, name, description, type, parent_id, status, created_by, created_at, updated_at, deleted_at)
SELECT d.id, d.tenant_id, d.name, d.description, 'department', NULL, 'active', NULL, d.created_at, d.updated_at, d.deleted_at
FROM departments d
WHERE NOT EXISTS (
    SELECT 1 FROM workspaces w
    WHERE w.tenant_id = d.tenant_id AND lower(w.name) = lower(d.name) AND w.deleted_at IS NULL
);

-- Re-point employees.department_id at workspaces instead of departments (IDs are shared, so no
-- UPDATE of the column values themselves is needed — only the FK constraint changes target).
ALTER TABLE employees DROP CONSTRAINT IF EXISTS employees_department_id_fkey;
ALTER TABLE employees
    ADD CONSTRAINT employees_department_id_fkey
    FOREIGN KEY (department_id) REFERENCES workspaces(id);

DROP TABLE departments;
