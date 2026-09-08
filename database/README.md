# Database resources

Flyway migrations used by the application live in
`api-server/src/main/resources/db/migration`. The top-level `database/migrations`
directory contains early architecture references only and must not be used as a
replacement for the application migration path.

Demo data is maintained separately from schema migrations:

- `scripts/seed.sh`: local/staging entrypoint;
- `scripts/seed_demo.sql`: deterministic three-company functional dataset;
- `scripts/verify_demo_seed.sql`: integrity and tenant-isolation assertions;
- `scripts/seed_perf.sql`: optional large performance dataset, never loaded by default;
- `scripts/seed_perf_cleanup.sql`: removes only the optional performance dataset.

Run the curated dataset with:

```bash
make seed
```

See `docs/testing/demo-seed-data.md` for accounts, roles and the relationship map.
Never execute demo or performance seeds against production.
