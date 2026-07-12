ALTER TABLE face_profiles
    ADD COLUMN embedding_deleted BOOLEAN NOT NULL DEFAULT false;
