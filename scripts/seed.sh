#!/usr/bin/env bash
# Curated local/staging demo seed.
#
# The seed is intentionally small and deterministic:
#   - 1 standalone Platform Admin (created by Flyway; never owns/joins a tenant)
#   - 5 companies across Active/Trial/Expired/Cancelled lifecycle states
#   - 1 fully populated company with exactly 15 authenticated members
#   - 15/07–05/09/2026 attendance, random-check and billing history
#
# Safe to run repeatedly. It archives the legacy v2 demo tenants, reconciles the
# deterministic v4 records, then runs database-level integrity assertions.

set -euo pipefail

SEED_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_PROJECT_DIR="$(cd "$SEED_SCRIPT_DIR/.." && pwd)"
SEED_ENV_FILE="$SEED_PROJECT_DIR/.env"

if [ -f "$SEED_ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    source "$SEED_ENV_FILE"
    set +a
fi

SEED_DB_CONTAINER="${DB_CONTAINER:-fams-postgres}"
SEED_DB_USER="${DB_USER:-fams_user}"
SEED_DB_NAME="${DB_NAME:-fams_db}"
SEED_DB_HOST="${DB_HOST:-localhost}"
SEED_DB_PORT="${DB_PORT:-5433}"
SEED_SQL_FILE="$SEED_SCRIPT_DIR/seed_demo.sql"
SEED_VERIFY_FILE="$SEED_SCRIPT_DIR/verify_demo_seed.sql"

run_seed_psql() {
    local sql_file="$1"
    if command -v docker >/dev/null 2>&1 \
        && docker exec "$SEED_DB_CONTAINER" true >/dev/null 2>&1; then
        docker exec -i "$SEED_DB_CONTAINER" \
            psql -X -v ON_ERROR_STOP=1 -U "$SEED_DB_USER" -d "$SEED_DB_NAME" < "$sql_file"
        return
    fi

    if ! command -v psql >/dev/null 2>&1; then
        echo "ERROR: PostgreSQL client is unavailable and container '$SEED_DB_CONTAINER' is not running." >&2
        exit 1
    fi

    PGPASSWORD="${DB_PASSWORD:-}" psql -X -v ON_ERROR_STOP=1 \
        -h "$SEED_DB_HOST" -p "$SEED_DB_PORT" \
        -U "$SEED_DB_USER" -d "$SEED_DB_NAME" -f "$sql_file"
}

echo "=== FAMS curated demo seed v4 ==="
echo "Database: $SEED_DB_NAME"
echo "Loading deterministic demo records..."
run_seed_psql "$SEED_SQL_FILE"

echo "Validating tenant isolation, roles and relationships..."
run_seed_psql "$SEED_VERIFY_FILE"

cat <<'SUMMARY'

=== Seed completed successfully ===

Default password for every demo account: Admin@1234

Platform:
  admin@fams.com                       PLATFORM_ADMIN (no company membership)

Primary company — Công ty CP Xây dựng An Phát:
  admin.anphat@fams.test               TENANT_ADMIN
  hr.anphat@fams.test                  HR_MANAGER
  hr.support.anphat@fams.test          HR_MANAGER
  supervisor.hq@fams.test              SITE_SUPERVISOR — Trụ sở Hà Nội
  supervisor.tayho@fams.test           SITE_SUPERVISOR — Công trình Tây Hồ
  supervisor.caugiay@fams.test         SITE_SUPERVISOR — Công trình Cầu Giấy
  supervisor.donganh@fams.test         SITE_SUPERVISOR — Công trình Đông Anh
  duy.anh@fams.test                    EMPLOYEE
  minh.quan@fams.test                  EMPLOYEE
  van.khoa@fams.test                   EMPLOYEE
  thi.lan@fams.test                    EMPLOYEE
  gia.bao@fams.test                    EMPLOYEE
  ngoc.mai@fams.test                   EMPLOYEE
  thanh.tung@fams.test                 EMPLOYEE
  thu.trang@fams.test                  EMPLOYEE

Lightweight companies:
  owner.minhlong@fams.test             TENANT_ADMIN — Logistics Minh Long
  owner.saoviet@fams.test              TENANT_ADMIN — Dịch vụ Sao Việt
  owner.phuchung@fams.test             TENANT_ADMIN — Nội thất Phúc Hưng
  owner.bacnam@fams.test               TENANT_ADMIN — Cơ điện Bắc Nam

All accounts above are active and email-verified. Business history spans 15/07–05/09/2026.
See docs/testing/demo-seed-data.md for the complete data map.
SUMMARY
