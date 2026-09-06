-- payOS requires orderCode to be unique for the lifetime of a merchant account, not merely
-- inside the current database. Local/demo databases are rebuilt frequently, so restarting the
-- sequence at 100000 reuses codes that payOS still remembers and checkout creation is rejected.
--
-- Millisecond Unix time is safely below JavaScript's MAX_SAFE_INTEGER and makes a newly rebuilt
-- database continue from a fresh merchant-wide range. Existing larger codes remain monotonic.
SELECT setval(
    'billing_order_code_seq',
    GREATEST(
        COALESCE((SELECT MAX(order_code) + 1 FROM billing_orders), 1),
        FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT
    ),
    false
);
