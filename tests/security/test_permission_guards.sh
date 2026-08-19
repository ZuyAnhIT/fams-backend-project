#!/usr/bin/env bash
# Tests for RBAC @PreAuthorize guard coverage (task 146)
# Verifies that protected endpoints return 401 without a token
# and 403 when the caller lacks the required permission.
#
# Required env vars:
#   BASE_URL         - e.g. http://localhost:8080
#   TENANT_ID        - a valid tenant UUID
#   SITE_ID          - a valid site UUID within TENANT_ID
#   EMPLOYEE_TOKEN   - JWT for a plain employee (has checkins:create/read but NOT employees:create etc.)
#   HR_TOKEN         - JWT for an HR manager (has violations:update, checkins:list, etc.)
#
# Optional:
#   SHIFT_ID         - a shift UUID for shift endpoints
#   SITE_ID2         - a second site UUID for assignment endpoints
#   CHECKIN_ID       - a valid check-in UUID for override/detail tests
#   VIOLATION_ID     - a valid violation UUID for violation tests

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TENANT_ID="${TENANT_ID:-00000000-0000-0000-0000-000000000001}"
SITE_ID="${SITE_ID:-00000000-0000-0000-0000-000000000002}"
SHIFT_ID="${SHIFT_ID:-00000000-0000-0000-0000-000000000003}"
CHECKIN_ID="${CHECKIN_ID:-00000000-0000-0000-0000-000000000004}"
VIOLATION_ID="${VIOLATION_ID:-00000000-0000-0000-0000-000000000005}"
EMPLOYEE_TOKEN="${EMPLOYEE_TOKEN:-}"
HR_TOKEN="${HR_TOKEN:-}"

PASS=0
FAIL=0

run_test() {
    local name="$1" expected_status="$2"
    local actual_status
    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${@:3}")
    if [ "$actual_status" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

T="$TENANT_ID"
S="$SITE_ID"

echo "=== 401 — No token ==="

run_test "employees:create — 401 no token" 401 \
    -X POST "$BASE_URL/api/v1/tenants/$T/employees" \
    -H "Content-Type: application/json" -d '{}'

run_test "employees:list — 401 no token" 401 \
    "$BASE_URL/api/v1/tenants/$T/employees"

run_test "employees:update — 401 no token" 401 \
    -X PATCH "$BASE_URL/api/v1/tenants/$T/employees/$(uuidgen 2>/dev/null || echo 00000000-0000-0000-0000-000000000099)" \
    -H "Content-Type: application/json" -d '{}'

run_test "sites:create — 401 no token" 401 \
    -X POST "$BASE_URL/api/v1/tenants/$T/sites" \
    -H "Content-Type: application/json" -d '{}'

run_test "sites:list — 401 no token" 401 \
    "$BASE_URL/api/v1/tenants/$T/sites"

run_test "sites:update — 401 no token" 401 \
    -X PUT "$BASE_URL/api/v1/tenants/$T/sites/$S" \
    -H "Content-Type: application/json" -d '{}'

run_test "assignments:create — 401 no token" 401 \
    -X POST "$BASE_URL/api/v1/tenants/$T/sites/$S/assignments" \
    -H "Content-Type: application/json" -d '{}'

run_test "assignments:delete — 401 no token" 401 \
    -X DELETE "$BASE_URL/api/v1/tenants/$T/sites/$S/assignments/$(uuidgen 2>/dev/null || echo 00000000-0000-0000-0000-000000000099)"

run_test "shifts:create — 401 no token" 401 \
    -X POST "$BASE_URL/api/v1/tenants/$T/sites/$S/shifts" \
    -H "Content-Type: application/json" -d '{}'

run_test "checkins:override — 401 no token" 401 \
    -X PATCH "$BASE_URL/api/v1/tenants/$T/checkin/$CHECKIN_ID/override" \
    -H "Content-Type: application/json" -d '{}'

run_test "violations:confirm — 401 no token" 401 \
    -X POST "$BASE_URL/api/v1/tenants/$T/violations/$VIOLATION_ID/confirm" \
    -H "Content-Type: application/json" -d '{}'

run_test "violations:dismiss — 401 no token" 401 \
    -X POST "$BASE_URL/api/v1/tenants/$T/violations/$VIOLATION_ID/dismiss" \
    -H "Content-Type: application/json" -d '{"reason":"test"}'

run_test "randomchecks:configure (config create) — 401 no token" 401 \
    -X POST "$BASE_URL/api/v1/tenants/$T/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" -d '{}'

run_test "randomchecks:configure (generate) — 401 no token" 401 \
    -X POST "$BASE_URL/api/v1/tenants/$T/scheduled-checks/generate"

echo ""
echo "=== 403 — Employee token on HR-only endpoints ==="

if [ -z "$EMPLOYEE_TOKEN" ]; then
    echo "SKIP: EMPLOYEE_TOKEN not set — skipping 403 tests"
else
    run_test "employees:create — 403 employee role" 403 \
        -X POST "$BASE_URL/api/v1/tenants/$T/employees" \
        -H "Authorization: Bearer $EMPLOYEE_TOKEN" \
        -H "Content-Type: application/json" -d '{}'

    run_test "sites:create — 403 employee role" 403 \
        -X POST "$BASE_URL/api/v1/tenants/$T/sites" \
        -H "Authorization: Bearer $EMPLOYEE_TOKEN" \
        -H "Content-Type: application/json" -d '{}'

    run_test "assignments:create — 403 employee role" 403 \
        -X POST "$BASE_URL/api/v1/tenants/$T/sites/$S/assignments" \
        -H "Authorization: Bearer $EMPLOYEE_TOKEN" \
        -H "Content-Type: application/json" -d '{}'

    run_test "shifts:create — 403 employee role" 403 \
        -X POST "$BASE_URL/api/v1/tenants/$T/sites/$S/shifts" \
        -H "Authorization: Bearer $EMPLOYEE_TOKEN" \
        -H "Content-Type: application/json" -d '{}'

    run_test "checkins:override — 403 employee role" 403 \
        -X PATCH "$BASE_URL/api/v1/tenants/$T/checkin/$CHECKIN_ID/override" \
        -H "Authorization: Bearer $EMPLOYEE_TOKEN" \
        -H "Content-Type: application/json" -d '{"status":"valid","reason":"test"}'

    run_test "violations:list — 403 employee role" 403 \
        "$BASE_URL/api/v1/tenants/$T/violations" \
        -H "Authorization: Bearer $EMPLOYEE_TOKEN"

    run_test "violations:update (confirm) — 403 employee role" 403 \
        -X POST "$BASE_URL/api/v1/tenants/$T/violations/$VIOLATION_ID/confirm" \
        -H "Authorization: Bearer $EMPLOYEE_TOKEN" \
        -H "Content-Type: application/json" -d '{}'

    run_test "randomchecks:configure — 403 employee role" 403 \
        -X POST "$BASE_URL/api/v1/tenants/$T/random-check-configs/tenant-default" \
        -H "Authorization: Bearer $EMPLOYEE_TOKEN" \
        -H "Content-Type: application/json" -d '{}'
fi

echo ""
echo "=== #146 (2026-08-19): denied requests are now audited (self-contained, no env vars needed) ==="

ADMIN_TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}' \
    | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$ADMIN_TOKEN" ]; then
    echo "SKIP: could not log in as admin@fams.com — skipping audit-on-denial check"
else
    TS=$(date +%s)
    t_resp=$(curl -s -X POST "$BASE_URL/api/v1/tenants" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"name\":\"PermGuardAudit ${TS}\",\"slug\":\"permguard-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
    NEW_TENANT_ID=$(echo "$t_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

    reg_email="permguard_${TS}@example.com"
    curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/register" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$reg_email\",\"password\":\"TestPass1\",\"displayName\":\"Perm Guard Test\"}"
    docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
        "UPDATE users SET email_verified = true WHERE email = '$reg_email';" > /dev/null 2>&1 || true
    NON_MEMBER_TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"identifier\":\"$reg_email\",\"password\":\"TestPass1\"}" \
        | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

    if [ -z "$NEW_TENANT_ID" ] || [ -z "$NON_MEMBER_TOKEN" ]; then
        echo "SKIP: could not set up tenant/non-member user for audit-on-denial check"
    else
        # A user with no role on this tenant PATCHing its profile should be denied (403) — not
        # PLAN_LIMIT/business logic, purely an RBAC/tenant-scope denial.
        run_test "Non-member PATCH tenant — 403" 403 \
            -X PATCH "$BASE_URL/api/v1/tenants/$NEW_TENANT_ID" \
            -H "Authorization: Bearer $NON_MEMBER_TOKEN" \
            -H "Content-Type: application/json" -d '{"name":"Should not be allowed"}'

        sleep 1
        audit_body=$(curl -s "$BASE_URL/api/v1/audit-logs?tenantId=$NEW_TENANT_ID&action=ACCESS_DENIED" \
            -H "Authorization: Bearer $ADMIN_TOKEN")
        if echo "$audit_body" | grep -q "\"action\":\"ACCESS_DENIED\""; then
            echo "PASS: audit_logs has an ACCESS_DENIED row for the denied PATCH"
            PASS=$((PASS + 1))
        else
            echo "FAIL: no ACCESS_DENIED row found in audit_logs for tenant $NEW_TENANT_ID"
            FAIL=$((FAIL + 1))
        fi
    fi
fi

echo ""
echo "=== Results ==="
echo "PASS: $PASS  FAIL: $FAIL"
[ "$FAIL" -eq 0 ]
