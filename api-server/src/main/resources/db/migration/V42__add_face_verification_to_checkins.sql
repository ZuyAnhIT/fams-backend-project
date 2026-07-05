ALTER TABLE checkins
    ADD COLUMN face_verified     BOOLEAN,
    ADD COLUMN liveness_verified BOOLEAN,
    ADD COLUMN face_verify_score DOUBLE PRECISION;
