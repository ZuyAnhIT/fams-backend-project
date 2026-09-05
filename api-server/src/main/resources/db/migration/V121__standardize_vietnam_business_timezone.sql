-- FAMS is currently a Vietnam-only product. Keep every business calendar and shift window on
-- one canonical IANA zone; TIMESTAMPTZ values remain absolute instants and are not rewritten.
UPDATE tenants
SET timezone = 'Asia/Ho_Chi_Minh'
WHERE timezone IS DISTINCT FROM 'Asia/Ho_Chi_Minh';

UPDATE sites
SET timezone = 'Asia/Ho_Chi_Minh'
WHERE timezone IS DISTINCT FROM 'Asia/Ho_Chi_Minh';

ALTER TABLE tenants ALTER COLUMN timezone SET DEFAULT 'Asia/Ho_Chi_Minh';
ALTER TABLE sites ALTER COLUMN timezone SET DEFAULT 'Asia/Ho_Chi_Minh';

ALTER TABLE tenants
    ADD CONSTRAINT ck_tenants_vietnam_timezone CHECK (timezone = 'Asia/Ho_Chi_Minh');
ALTER TABLE sites
    ADD CONSTRAINT ck_sites_vietnam_timezone CHECK (timezone = 'Asia/Ho_Chi_Minh');
