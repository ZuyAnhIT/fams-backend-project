-- Platform-staff invitations: a Platform Admin invites someone by email to join FAMS's own
-- internal team (platform-scoped role, tenant_id IS NULL) — mirrors employee_invitations
-- (V18) but deliberately has NO tenant_id, since this is onboarding onto the platform itself,
-- not into any customer's company.
CREATE TABLE platform_invitations (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email       VARCHAR(255) NOT NULL,
    token       UUID NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    status      VARCHAR(20) NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending', 'accepted', 'cancelled', 'expired')),
    invited_by  UUID NOT NULL REFERENCES users(id),
    role_id     UUID REFERENCES roles(id),
    first_name  VARCHAR(100),
    last_name   VARCHAR(100),
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ
);

CREATE INDEX idx_platform_inv_email  ON platform_invitations(email);
CREATE INDEX idx_platform_inv_token ON platform_invitations(token);
CREATE INDEX idx_platform_inv_status ON platform_invitations(status);
