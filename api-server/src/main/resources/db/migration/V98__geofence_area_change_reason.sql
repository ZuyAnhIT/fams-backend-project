-- Adds computed polygon area and an optional change-reason note to geofences (task 56/57/58 gaps).
ALTER TABLE geofences
    ADD COLUMN area_sqm DOUBLE PRECISION,
    ADD COLUMN change_reason TEXT;
