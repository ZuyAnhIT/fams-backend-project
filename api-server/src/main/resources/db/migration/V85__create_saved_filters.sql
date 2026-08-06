-- Story: "Lưu bộ lọc thường dùng" — personal saved filters for large list screens (violations,
-- checkins, employees, reports...). Scoped per user+tenant+resourceType — a saved filter is
-- private to the person who created it (matches the user story's own wording: "tôi muốn lưu
-- filter", not "team muốn chia sẻ filter"), same trust boundary as personal notification
-- settings. filter_params is opaque JSONB — the backend never interprets its shape, it's just
-- the exact query params the FE list screen re-applies, so no new endpoint/migration is needed
-- when a list screen adds a new filter field.
CREATE TABLE saved_filters (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id      UUID NOT NULL REFERENCES tenants(id),
    user_id        UUID NOT NULL REFERENCES users(id),
    resource_type  VARCHAR(50)  NOT NULL,
    name           VARCHAR(100) NOT NULL,
    filter_params  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    is_default     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ
);

CREATE INDEX idx_saved_filters_user_resource
    ON saved_filters(tenant_id, user_id, resource_type)
    WHERE deleted_at IS NULL;

-- A user can have at most 1 "default" saved filter per resource type (auto-applied when the
-- list screen loads) — partial unique index, only enforced among non-deleted, is_default=true rows.
CREATE UNIQUE INDEX uq_saved_filters_one_default
    ON saved_filters(tenant_id, user_id, resource_type)
    WHERE deleted_at IS NULL AND is_default = TRUE;

-- Name must be unique per user+resourceType (case-insensitive) among live rows — avoids "Vi phạm
-- tuần này" x3 cluttering the dropdown with no way to tell them apart.
CREATE UNIQUE INDEX uq_saved_filters_name
    ON saved_filters(tenant_id, user_id, resource_type, lower(name))
    WHERE deleted_at IS NULL;
