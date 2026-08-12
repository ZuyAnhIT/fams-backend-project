#!/usr/bin/env bash
# Tests for POST /api/v1/tenants/{tenantId}/violations/{violationId}/confirm
# Covers task 116 (HR confirms a violation as valid)
# Usage: BASE_URL=http://localhost:8080 bash test_hr_confirm_violation.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1"
    local expected_status="$2"
    local curl_args=("${@:3}")
    actual=$(curl -s -o /dev/null -w "%{http_code}" "${curl_args[@]}")
    if [ "$actual" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== HR Confirm Violation Tests (task 116) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"ownerEmail\":\"admin@fams.com\",\"name\":\"Confirm Corp ${TS}\",\"slug\":\"confirm-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

INVITE_EMAIL="confirm.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Confirm\",\"lastName\":\"Emp\"}")
if [ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation"; exit 1; fi

INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

emp_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$INVITE_EMAIL\",\"password\":\"Employee@1234\"}")
if [ "$(echo "$emp_login" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: employee login"; exit 1; fi
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id=e.user_id WHERE u.email='$INVITE_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

SITE_ID=$(cat /proc/sys/kernel/random/uuid)

seed_violation() {
    docker exec fams-postgres psql -U fams_user -d fams_db -c \
        "INSERT INTO violations (id, tenant_id, employee_id, site_id, violation_type, check_date, resolved, created_at, updated_at)
         VALUES (uuid_generate_v4(), '$TENANT_ID', '$EMP_ID', '$SITE_ID', 'no_response', '2026-07-03', false, now(), now());" \
        > /dev/null
    docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT id FROM violations WHERE tenant_id='$TENANT_ID' AND employee_id='$EMP_ID' AND resolved=false ORDER BY created_at DESC LIMIT 1;" \
        | tr -d ' \n'
}

VIOLATION_ID=$(seed_violation)
echo "Setup complete. TENANT_ID=$TENANT_ID  VIOLATION_ID=$VIOLATION_ID"
echo ""

CONFIRM_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/violations/$VIOLATION_ID/confirm"

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No token → 401 ---"
run_test "Unauthenticated" 401 -s -X POST "$CONFIRM_URL" \
    -H "Content-Type: application/json"
echo ""

# ── Test 2: Employee token (no violations:update perm) → 403 ─────────────────
echo "--- Test 2: Employee without violations:update → 403 ---"
run_test "Employee forbidden" 403 -s -X POST "$CONFIRM_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $EMP_TOKEN"
echo ""

# ── Test 3: Happy path — confirm without note → 200 ──────────────────────────
echo "--- Test 3: Confirm violation without note → 200 ---"
confirm_resp=$(curl -s -w "\n%{http_code}" -X POST "$CONFIRM_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
confirm_body=$(echo "$confirm_resp" | head -n -1)
confirm_status=$(echo "$confirm_resp" | tail -n 1)
if [ "$confirm_status" -eq 200 ]; then
    echo "PASS: Confirm returned HTTP 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 200, got $confirm_status — $confirm_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Response fields correct ──────────────────────────────────────────
echo "--- Test 4: Response has resolution=confirmed and resolved=true ---"
if [ "$confirm_status" -eq 200 ]; then
    if echo "$confirm_body" | grep -q '"confirmed"' && echo "$confirm_body" | grep -q '"resolved":true'; then
        echo "PASS: resolution=confirmed and resolved=true"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Unexpected response — $confirm_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Confirming already-resolved violation → 409 ──────────────────────
echo "--- Test 5: Double-confirm → 409 ---"
run_test "Already resolved → 409" 409 -s -X POST "$CONFIRM_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 6: Confirm with optional note → 200 and note stored ─────────────────
echo "--- Test 6: Confirm with note → 200 and note returned ---"
VIOLATION_ID2=$(seed_violation)
NOTE_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/violations/$VIOLATION_ID2/confirm"
note_resp=$(curl -s -w "\n%{http_code}" -X POST "$NOTE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"note":"GPS clearly outside the geofence boundary."}')
note_body=$(echo "$note_resp" | head -n -1)
note_status=$(echo "$note_resp" | tail -n 1)
if [ "$note_status" -eq 200 ]; then
    if echo "$note_body" | grep -q "GPS clearly outside"; then
        echo "PASS: Note returned in response"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Note not in response — $note_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $note_status — $note_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: Non-existent violation → 404 ─────────────────────────────────────
echo "--- Test 7: Non-existent violationId → 404 ---"
FAKE_ID="00000000-0000-0000-0000-000000000099"
run_test "Violation not found" 404 -s -X POST \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/violations/$FAKE_ID/confirm" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 8: DB state — violation is now marked resolved and confirmed ──────────
echo "--- Test 8: DB state shows resolved=true and resolution=confirmed ---"
db_resolved=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT resolved FROM violations WHERE id='$VIOLATION_ID';" | tr -d ' \n')
db_resolution=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT resolution FROM violations WHERE id='$VIOLATION_ID';" | tr -d ' \n')
if [ "$db_resolved" = "t" ] && [ "$db_resolution" = "confirmed" ]; then
    echo "PASS: DB shows resolved=t and resolution=confirmed"
    PASS=$((PASS + 1))
else
    echo "FAIL: DB shows resolved=$db_resolved resolution=$db_resolution"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
