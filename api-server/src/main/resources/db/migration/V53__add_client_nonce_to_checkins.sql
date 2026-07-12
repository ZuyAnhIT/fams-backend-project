ALTER TABLE checkins
    ADD COLUMN client_nonce UUID;

CREATE UNIQUE INDEX checkins_client_nonce_unique
    ON checkins (client_nonce)
    WHERE client_nonce IS NOT NULL;
