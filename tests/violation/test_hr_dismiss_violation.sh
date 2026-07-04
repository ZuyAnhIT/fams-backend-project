#!/usr/bin/env bash
# Tests for POST /api/v1/tenants/{tenantId}/violations/{violationId}/dismiss
# Covers task 117 (HR dismisses a violation with a mandatory reason)
# Usage: BASE_URL=http://localhost:8080 bash test_hr_dismiss_violation.sh

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

echo "=== HR Dismiss Violation Tests (task 117) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Dismiss Corp ${TS}\",\"slug\":\"dismiss-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

INVITE_EMAIL="dismiss.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Dismiss\",\"lastName\":\"Emp\"}")
if [ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation"; exit 1; fi

INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

emp_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"password\":\"Employee@1234\"}")
if [ "$(echo "$emp_login" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: employee login"; exit 1; fi
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id=e.user_id WHERE u.email='$INVITE_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

SITE_ID=$(cat /proc/sys/kernel/random/uuid)

seed_violation() {
    docker exec fams-postgres psql -U fams_user -d fams_db -c \
        "INSERT INTO violations (id, tenant_id, employee_id, site_id, violation_type, check_date, resolved, created_at, updated_at)
         VALUES (uuid_generate_v4(), '$TENANT_ID', '$EMP_ID', '$SITE_ID', 'face_fail', '2026-07-03', false, now(), now());" \
        > /dev/null
    docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT id FROM violations WHERE tenant_id='$TENANT_ID' AND employee_id='$EMP_ID' AND resolved=false ORDER BY created_at DESC LIMIT 1;" \
        | tr -d ' \n'
}

VIOLATION_ID=$(seed_violation)
echo "Setup complete. TENANT_ID=$TENANT_ID  VIOLATION_ID=$VIOLATION_ID"
echo ""

DISMISS_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/violations/$VIOLATION_ID/dismiss"

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No token → 401 ---"
run_test "Unauthenticated" 401 -s -X POST "$DISMISS_URL" \
    -H "Content-Type: application/json" \
    -d '{"reason":"test"}'
echo ""

# ── Test 2: Employee token (no violations:update perm) → 403 ─────────────────
echo "--- Test 2: Employee without violations:update → 403 ---"
run_test "Employee forbidden" 403 -s -X POST "$DISMISS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"reason":"test"}'
echo ""

# ── Test 3: Missing reason → 400 ─────────────────────────────────────────────
echo "--- Test 3: Missing reason → 400 ---"
run_test "Blank reason rejected" 400 -s -X POST "$DISMISS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"reason":""}'
echo ""

# ── Test 4: No body → 400 ────────────────────────────────────────────────────
echo "--- Test 4: No body → 400 ---"
run_test "No body rejected" 400 -s -X POST "$DISMISS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 5: Happy path — dismiss with valid reason → 200 ─────────────────────
echo "--- Test 5: Dismiss with valid reason → 200 ---"
dismiss_resp=$(curl -s -w "\n%{http_code}" -X POST "$DISMISS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"reason":"Employee was at an approved off-site location confirmed by manager."}')
dismiss_body=$(echo "$dismiss_resp" | head -n -1)
dismiss_status=$(echo "$dismiss_resp" | tail -n 1)
if [ "$dismiss_status" -eq 200 ]; then
    echo "PASS: Dismiss returned HTTP 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 200, got $dismiss_status — $dismiss_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Response fields correct ──────────────────────────────────────────
echo "--- Test 6: Response has resolution=dismissed, resolved=true, reason stored ---"
if [ "$dismiss_status" -eq 200 ]; then
    if echo "$dismiss_body" | grep -q '"dismissed"' && \
       echo "$dismiss_body" | grep -q '"resolved":true' && \
       echo "$dismiss_body" | grep -q "approved off-site"; then
        echo "PASS: resolution=dismissed, resolved=true, reason present"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Unexpected response — $dismiss_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: Double-dismiss → 409 ─────────────────────────────────────────────
echo "--- Test 7: Double-dismiss → 409 ---"
run_test "Already resolved → 409" 409 -s -X POST "$DISMISS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"reason":"Second attempt"}'
echo ""

# ── Test 8: Non-existent violation → 404 ─────────────────────────────────────
echo "--- Test 8: Non-existent violationId → 404 ---"
FAKE_ID="00000000-0000-0000-0000-000000000099"
run_test "Violation not found" 404 -s -X POST \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/violations/$FAKE_ID/dismiss" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"reason":"irrelevant"}'
echo ""

# ── Test 9: DB state — resolved=true, resolution=dismissed, reason stored ─────
echo "--- Test 9: DB state shows resolved=true, resolution=dismissed ---"
db_resolved=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT resolved FROM violations WHERE id='$VIOLATION_ID';" | tr -d ' \n')
db_resolution=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT resolution FROM violations WHERE id='$VIOLATION_ID';" | tr -d ' \n')
db_reason=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT resolution_reason FROM violations WHERE id='$VIOLATION_ID';" | tr -d '\n' | xargs)
if [ "$db_resolved" = "t" ] && [ "$db_resolution" = "dismissed" ] && [ -n "$db_reason" ]; then
    echo "PASS: DB shows resolved=t, resolution=dismissed, reason present"
    PASS=$((PASS + 1))
else
    echo "FAIL: DB shows resolved=$db_resolved resolution=$db_resolution reason='$db_reason'"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
