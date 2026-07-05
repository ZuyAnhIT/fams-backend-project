CREATE TABLE face_profiles (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenants(id),
    employee_id      UUID NOT NULL REFERENCES employees(id),
    consent_given    BOOLEAN NOT NULL DEFAULT FALSE,
    consent_given_at TIMESTAMPTZ,
    status           VARCHAR(20) NOT NULL DEFAULT 'not_enrolled'
                         CHECK (status IN ('not_enrolled','pending','enrolled','revoked')),
    embedding        DOUBLE PRECISION[],
    enrolled_at      TIMESTAMPTZ,
    revoked_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, employee_id)
);

CREATE INDEX idx_face_profiles_tenant_employee ON face_profiles(tenant_id, employee_id);

-- face_id:manage permission — granted to HR_MANAGER and TENANT_ADMIN
INSERT INTO permissions (name, resource, action, description)
VALUES ('face_id:manage', 'face_id', 'manage', 'Manage employee Face ID enrollment and consent')
ON CONFLICT (name) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.name IN ('HR_MANAGER', 'TENANT_ADMIN', 'PLATFORM_ADMIN')
  AND r.tenant_id IS NULL
  AND p.name = 'face_id:manage'
ON CONFLICT DO NOTHING;
