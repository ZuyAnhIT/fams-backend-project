-- Materialize the checkout deadline so stale sessions can be closed proactively by a
-- scheduler, instead of relying on the employee reopening the app after the shift.
ALTER TABLE checkins
    ADD COLUMN session_expires_at TIMESTAMPTZ;

-- Backfill from the immutable shift snapshot stored on each check-in. The site timezone is
-- used because shift times are wall-clock values local to the site.
UPDATE checkins c
SET session_expires_at = CASE
    WHEN c.shift_end_time IS NULL THEN
        (((c.check_in_at AT TIME ZONE COALESCE(NULLIF(s.timezone, ''), 'UTC'))::date + 1)::timestamp
            AT TIME ZONE COALESCE(NULLIF(s.timezone, ''), 'UTC'))
    ELSE
        ((
            (
                (c.check_in_at AT TIME ZONE COALESCE(NULLIF(s.timezone, ''), 'UTC'))::date
                - CASE
                    WHEN COALESCE(c.shift_allow_overnight, FALSE)
                         AND (c.check_in_at AT TIME ZONE COALESCE(NULLIF(s.timezone, ''), 'UTC'))::time
                             < c.shift_end_time
                    THEN 1 ELSE 0
                  END
                + CASE WHEN COALESCE(c.shift_allow_overnight, FALSE) THEN 1 ELSE 0 END
            )::date + c.shift_end_time
        ) AT TIME ZONE COALESCE(NULLIF(s.timezone, ''), 'UTC'))
        + make_interval(mins => GREATEST(COALESCE(c.shift_late_checkout_minutes, 0), 0))
END
FROM sites s
WHERE s.id = c.site_id
  AND c.session_expires_at IS NULL;

CREATE INDEX idx_checkins_open_session_expiry
    ON checkins (session_expires_at)
    WHERE check_out_at IS NULL
      AND session_closed_at IS NULL
      AND deleted_at IS NULL;

COMMENT ON COLUMN checkins.session_expires_at IS
    'Immutable checkout deadline calculated from the shift snapshot and site timezone at check-in';
