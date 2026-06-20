-- Convert CHAR columns to VARCHAR to satisfy Hibernate schema validation
ALTER TABLE tenants ALTER COLUMN country_code  TYPE VARCHAR(2)  USING country_code::VARCHAR;
ALTER TABLE tenants ALTER COLUMN currency_code TYPE VARCHAR(3)  USING currency_code::VARCHAR;
