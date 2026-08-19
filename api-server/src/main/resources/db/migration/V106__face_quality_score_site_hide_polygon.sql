-- #127 (2026-08-18): fams-ai already computes an anti-spoof/liveness confidence score per photo
-- during enrollment (both the raw-photo and active-liveness challenge paths) but discards it —
-- never persisted, never returned. Adding columns to persist it so the Face ID report (#127 AC)
-- can surface a quality score. Mirrors the existing embedding/pending_embedding promote-on-approve
-- pattern already used by this table.
ALTER TABLE face_profiles
    ADD COLUMN quality_score double precision,
    ADD COLUMN pending_quality_score double precision;

-- #130 (2026-08-18): AC requires the geofence polygon to be hideable from the employee-facing
-- check-in map per site policy — no such flag existed anywhere in the data model. Defaults to
-- false (unchanged behavior: polygon still shown) so this is opt-in per site, e.g. for a
-- security-sensitive site an HR admin wants to keep the exact boundary shape from employees.
ALTER TABLE sites
    ADD COLUMN hide_polygon_from_employee boolean NOT NULL DEFAULT false;
