#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/violations/{violationId} (HR detail)
# Covers task 115 (HR views violation detail with evidence)
# Usage: BASE_URL=http://localhost:8080 bash test_hr_violation_detail.sh

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

echo "=== HR Violation Detail Tests (task 115) ==="
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
    -d "{\"name\":\"Violation Detail Corp ${TS}\",\"slug\":\"viol-detail-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

INVITE_EMAIL="viol.det.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Viol\",\"lastName\":\"Detail\"}")
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
docker exec fams-postgres psql -U fams_user -d fams_db -c \
    "INSERT INTO violations (id, tenant_id, employee_id, site_id, violation_type, check_date, description, resolved, created_at)
     VALUES (uuid_generate_v4(), '$TENANT_ID', '$EMP_ID', '$SITE_ID', 'location_fail', '2026-07-02', 'Outside geofence', false, now());" \
    > /dev/null
VIOLATION_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT id FROM violations WHERE tenant_id='$TENANT_ID' AND employee_id='$EMP_ID' AND violation_type='location_fail' LIMIT 1;" \
    | tr -d ' \n')

echo "Setup complete. TENANT_ID=$TENANT_ID  VIOLATION_ID=$VIOLATION_ID"
echo ""

DETAIL_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/violations/$VIOLATION_ID"

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No token → 401 ---"
run_test "Unauthenticated" 401 -s "$DETAIL_URL"
echo ""

# ── Test 2: Employee token (no violations:read perm) → 403 ───────────────────
echo "--- Test 2: Employee without violations:read → 403 ---"
run_test "Employee forbidden" 403 -s \
    -H "Authorization: Bearer $EMP_TOKEN" "$DETAIL_URL"
echo ""

# ── Test 3: Platform admin gets detail → 200 ─────────────────────────────────
echo "--- Test 3: Platform admin gets violation detail → 200 ---"
detail_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" "$DETAIL_URL")
detail_body=$(echo "$detail_resp" | head -n -1)
detail_status=$(echo "$detail_resp" | tail -n 1)
if [ "$detail_status" -eq 200 ]; then
    echo "PASS: Detail returned HTTP 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 200, got $detail_status — $detail_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Response contains required fields ─────────────────────────────────
echo "--- Test 4: Response contains required fields ---"
if [ "$detail_status" -eq 200 ]; then
    MISSING=""
    for field in '"id"' '"employeeId"' '"siteId"' '"violationType"' '"checkDate"' '"resolved"' '"createdAt"'; do
        if ! echo "$detail_body" | grep -q "$field"; then
            MISSING="$MISSING $field"
        fi
    done
    if [ -z "$MISSING" ]; then
        echo "PASS: All required fields present"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing fields:$MISSING — $detail_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Correct violation data returned ───────────────────────────────────
echo "--- Test 5: Correct violationType and resolved values ---"
if [ "$detail_status" -eq 200 ]; then
    if echo "$detail_body" | grep -q '"location_fail"' && echo "$detail_body" | grep -q '"resolved":false'; then
        echo "PASS: violationType=location_fail and resolved=false confirmed"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Unexpected values — $detail_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: scheduledCheck is null (no random check linked) ───────────────────
echo "--- Test 6: scheduledCheck is null when not linked ---"
if [ "$detail_status" -eq 200 ]; then
    if echo "$detail_body" | grep -q '"scheduledCheck":null'; then
        echo "PASS: scheduledCheck is null as expected"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected scheduledCheck:null — $detail_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: checkResponse is null (no response linked) ────────────────────────
echo "--- Test 7: checkResponse is null when not linked ---"
if [ "$detail_status" -eq 200 ]; then
    if echo "$detail_body" | grep -q '"checkResponse":null'; then
        echo "PASS: checkResponse is null as expected"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected checkResponse:null — $detail_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: Non-existent violation → 404 ─────────────────────────────────────
echo "--- Test 8: Non-existent violationId → 404 ---"
FAKE_ID="00000000-0000-0000-0000-000000000099"
run_test "Violation not found" 404 -s \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/violations/$FAKE_ID"
echo ""

# ── Test 9: Wrong tenant → 404 (tenant isolation) ─────────────────────────────
echo "--- Test 9: Wrong tenant → 404 (tenant isolation) ---"
OTHER_TS=$((TS + 1))
other_t=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Other Corp ${OTHER_TS}\",\"slug\":\"other-corp-${OTHER_TS}\"}")
OTHER_TENANT_ID=$(echo "$other_t" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "Cross-tenant isolation" 404 -s \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/tenants/$OTHER_TENANT_ID/violations/$VIOLATION_ID"
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
