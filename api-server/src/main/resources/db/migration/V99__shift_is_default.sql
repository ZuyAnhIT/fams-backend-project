-- Adds a "default shift" concept per site (task 59/61 gap) — at most one default shift per site.
ALTER TABLE shifts
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT false;

CREATE UNIQUE INDEX uq_shifts_site_default ON shifts (site_id) WHERE is_default = true AND deleted_at IS NULL;
