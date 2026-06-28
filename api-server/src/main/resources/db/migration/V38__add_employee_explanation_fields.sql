ALTER TABLE checkins
    ADD COLUMN employee_note TEXT,
    ADD COLUMN employee_photo_url TEXT;

ALTER TABLE violations
    ADD COLUMN employee_note TEXT,
    ADD COLUMN employee_photo_url TEXT;
