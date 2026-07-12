CREATE TABLE notification_delivery_logs (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    notification_id UUID REFERENCES notifications(id),
    device_token    VARCHAR(512),
    channel         VARCHAR(50)  NOT NULL DEFAULT 'FCM',
    attempt_number  INT          NOT NULL DEFAULT 1,
    status          VARCHAR(50)  NOT NULL,
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_ndl_notification_id ON notification_delivery_logs(notification_id);
CREATE INDEX idx_ndl_status          ON notification_delivery_logs(status);
CREATE INDEX idx_ndl_created_at      ON notification_delivery_logs(created_at DESC);
