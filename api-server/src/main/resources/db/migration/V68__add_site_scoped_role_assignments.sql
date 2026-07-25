-- Site-scoped role assignment: a user_roles row with ZERO linked sites here means
-- "unrestricted across the whole tenant" (unchanged, existing behavior) — the same
-- empty-set-means-unrestricted convention already used by tenant_ip_whitelist. Adding 1+
-- rows here restricts that specific role assignment to only those sites (e.g. a
-- SITE_SUPERVISOR assigned to just their own site instead of the whole company).
CREATE TABLE user_role_sites (
    user_role_id UUID NOT NULL REFERENCES user_roles(id) ON DELETE CASCADE,
    site_id      UUID NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    PRIMARY KEY (user_role_id, site_id)
);

CREATE INDEX idx_user_role_sites_site_id ON user_role_sites(site_id);
