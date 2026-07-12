CREATE TABLE scheduled_job_status (
    job_name       VARCHAR(100) PRIMARY KEY,
    last_run_at    TIMESTAMPTZ  NOT NULL,
    last_status    VARCHAR(20)  NOT NULL,
    error_message  TEXT,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
