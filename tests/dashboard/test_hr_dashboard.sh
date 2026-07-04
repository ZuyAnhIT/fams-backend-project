#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/dashboard/hr
# Covers task 120 (HR dashboard — personnel, attendance, violations, sites)
# Usage: BASE_URL=http://localhost:8080 bash test_hr_dashboard.sh

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

echo "=== HR Dashboard Tests (task 120) ==="
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
    -d "{\"name\":\"HR Dash Corp ${TS}\",\"slug\":\"hr-dash-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Create a site
s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HQ Site","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Create an employee
INVITE_EMAIL="hr.dash.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"HR\",\"lastName\":\"DashEmp\"}")
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

echo "Setup complete. TENANT_ID=$TENANT_ID  EMP_ID=$EMP_ID  SITE_ID=$SITE_ID"
echo ""

HR_DASH_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/dashboard/hr"

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No token → 401 ---"
run_test "Unauthenticated" 401 -s "$HR_DASH_URL"
echo ""

# ── Test 2: Employee without employees:list → 403 ─────────────────────────────
echo "--- Test 2: Employee without employees:list → 403 ---"
run_test "Employee forbidden" 403 -s \
    -H "Authorization: Bearer $EMP_TOKEN" "$HR_DASH_URL"
echo ""

# ── Test 3: Platform admin gets HR dashboard → 200 ────────────────────────────
echo "--- Test 3: Platform admin gets HR dashboard → 200 ---"
dash_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" "$HR_DASH_URL")
dash_body=$(echo "$dash_resp" | head -n -1)
dash_status=$(echo "$dash_resp" | tail -n 1)
if [ "$dash_status" -eq 200 ]; then
    echo "PASS: HR dashboard returned HTTP 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 200, got $dash_status — $dash_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Response has all four top-level sections ──────────────────────────
echo "--- Test 4: Response contains personnel, attendance, violations, sites ---"
if [ "$dash_status" -eq 200 ]; then
    MISSING=""
    for field in '"personnel"' '"attendance"' '"violations"' '"sites"'; do
        if ! echo "$dash_body" | grep -q "$field"; then
            MISSING="$MISSING $field"
        fi
    done
    if [ -z "$MISSING" ]; then
        echo "PASS: All four dashboard sections present"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing:$MISSING — $dash_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: personnel.totalEmployees >= 1 ────────────────────────────────────
echo "--- Test 5: personnel.totalEmployees >= 1 ---"
if [ "$dash_status" -eq 200 ]; then
    total_emp=$(echo "$dash_body" | grep -o '"totalEmployees":[0-9]*' | cut -d: -f2)
    if [ -n "$total_emp" ] && [ "$total_emp" -ge 1 ]; then
        echo "PASS: totalEmployees=$total_emp"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected >= 1, got totalEmployees=$total_emp"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: sites.totalSites >= 1 ────────────────────────────────────────────
echo "--- Test 6: sites.totalSites >= 1 ---"
if [ "$dash_status" -eq 200 ]; then
    total_sites=$(echo "$dash_body" | grep -o '"totalSites":[0-9]*' | cut -d: -f2)
    if [ -n "$total_sites" ] && [ "$total_sites" -ge 1 ]; then
        echo "PASS: totalSites=$total_sites"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected >= 1, got totalSites=$total_sites"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    Fail=$((FAIL + 1))
fi
echo ""

# ── Test 7: violations section has unresolvedByType map ───────────────────────
echo "--- Test 7: violations.unresolvedByType present ---"
if [ "$dash_status" -eq 200 ]; then
    if echo "$dash_body" | grep -q '"unresolvedByType"'; then
        echo "PASS: unresolvedByType field present"
        PASS=$((PASS + 1))
    else
        echo "FAIL: unresolvedByType missing — $dash_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: Seed a violation, check unresolved count increases ────────────────
echo "--- Test 8: Seeded unresolved violation appears in count ---"
FAKE_SITE=$(cat /proc/sys/kernel/random/uuid)
docker exec fams-postgres psql -U fams_user -d fams_db -c \
    "INSERT INTO violations (id, tenant_id, employee_id, site_id, violation_type, check_date, resolved, affects_attendance, created_at, updated_at)
     VALUES (uuid_generate_v4(), '$TENANT_ID', '$EMP_ID', '$FAKE_SITE', 'no_response', CURRENT_DATE, false, false, now(), now());" \
    > /dev/null

dash_resp2=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" "$HR_DASH_URL")
dash_body2=$(echo "$dash_resp2" | head -n -1)
dash_status2=$(echo "$dash_resp2" | tail -n 1)
if [ "$dash_status2" -eq 200 ]; then
    unresolved=$(echo "$dash_body2" | grep -o '"unresolved":[0-9]*' | cut -d: -f2)
    if [ -n "$unresolved" ] && [ "$unresolved" -ge 1 ]; then
        echo "PASS: unresolved=$unresolved after seeding"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected >= 1 unresolved, got $unresolved"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected 200 on second call, got $dash_status2"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 9: attendance numeric fields present ─────────────────────────────────
echo "--- Test 9: attendance section has presentToday, lateToday, onSiteNow ---"
if [ "$dash_status" -eq 200 ]; then
    MISSING=""
    for field in '"presentToday"' '"lateToday"' '"onSiteNow"'; do
        if ! echo "$dash_body" | grep -q "$field"; then
            MISSING="$MISSING $field"
        fi
    done
    if [ -z "$MISSING" ]; then
        echo "PASS: All attendance fields present"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing:$MISSING"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
