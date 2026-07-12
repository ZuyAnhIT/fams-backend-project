CREATE TABLE user_devices (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id      UUID NOT NULL REFERENCES users(id),
    device_token VARCHAR(512) NOT NULL,
    platform     VARCHAR(50)  NOT NULL DEFAULT 'FCM',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_user_devices_token_unique
    ON user_devices(device_token)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_user_devices_user_id ON user_devices(user_id) WHERE deleted_at IS NULL;
