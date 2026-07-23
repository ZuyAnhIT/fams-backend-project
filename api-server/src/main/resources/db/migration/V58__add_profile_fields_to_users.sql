-- Issue #4 (docs/issues/ISSUES.md): profile screen only had displayName/phone/avatarUrl.
-- Adds the commonly-needed personal fields the user asked for (quê quán, năm sinh, and
-- reasonable neighbors gender/address) so the profile form has somewhere to save them.
ALTER TABLE users
    ADD COLUMN date_of_birth DATE,
    ADD COLUMN hometown VARCHAR(255),
    ADD COLUMN gender VARCHAR(20),
    ADD COLUMN address VARCHAR(500);
