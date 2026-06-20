-- RBAC: permissions, roles, role_permissions, user_roles

CREATE TABLE permissions (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        VARCHAR(200) NOT NULL UNIQUE,
    description TEXT,
    resource    VARCHAR(100) NOT NULL,
    action      VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE roles (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   UUID REFERENCES tenants(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    is_system   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ
);

-- System roles (tenant_id IS NULL) must be name-unique; tenant roles unique per tenant
CREATE UNIQUE INDEX uq_roles_system_name    ON roles (name)              WHERE tenant_id IS NULL;
CREATE UNIQUE INDEX uq_roles_tenant_name    ON roles (tenant_id, name)   WHERE tenant_id IS NOT NULL;

CREATE TABLE role_permissions (
    role_id       UUID NOT NULL REFERENCES roles(id)       ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_roles (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    role_id     UUID NOT NULL REFERENCES roles(id)   ON DELETE CASCADE,
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    assigned_by UUID REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT uq_user_roles UNIQUE (user_id, role_id, tenant_id)
);

CREATE INDEX idx_roles_tenant_id           ON roles(tenant_id);
CREATE INDEX idx_role_permissions_role_id  ON role_permissions(role_id);
CREATE INDEX idx_role_permissions_perm_id  ON role_permissions(permission_id);
CREATE INDEX idx_user_roles_user_id        ON user_roles(user_id);
CREATE INDEX idx_user_roles_role_id        ON user_roles(role_id);
CREATE INDEX idx_user_roles_tenant_id      ON user_roles(tenant_id);
