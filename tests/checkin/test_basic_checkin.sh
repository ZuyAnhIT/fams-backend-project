#!/usr/bin/env bash
# Tests for POST /api/v1/tenants/{tenantId}/checkin (basic GPS check-in)
# Usage: BASE_URL=http://localhost:8080 bash test_basic_checkin.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1"
    local expected_status="$2"
    local curl_args=("${@:3}")
    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${curl_args[@]}")
    if [ "$actual_status" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Basic GPS Check-in Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: admin login ────────────────────────────────────────────────────────
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."
echo ""

# ── Setup: tenant, site with geofence, shift ──────────────────────────────────
echo "--- Setup: Create tenant, site, geofence, shift ---"
TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"GPS Corp ${TS}\",\"slug\":\"gps-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Site at a known lat/lon (Hanoi)
s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Hanoi HQ","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Geofence around the site (~500m box)
geo_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/geofences" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
        "coordinates": [
            [105.8492, 21.0235],
            [105.8592, 21.0235],
            [105.8592, 21.0335],
            [105.8492, 21.0335],
            [105.8492, 21.0235]
        ],
        "bufferMeters": 50
    }')
if [ "$(echo "$geo_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: geofence"; exit 1; fi

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Morning","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "tenant=$TENANT_ID  site=$SITE_ID"
echo ""

# ── Setup: invite employee, accept, login ─────────────────────────────────────
echo "--- Setup: Invite and accept invitation ---"
INVITE_EMAIL="gps.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"GPS\",\"lastName\":\"Tester\"}")
if [ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation"; exit 1; fi

INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
if [ -z "$INV_TOKEN" ]; then echo "SETUP FAILED: invitation token"; exit 1; fi

accept_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}")
if [ "$(echo "$accept_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: accept invitation"; exit 1; fi

emp_login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"password\":\"Employee@1234\"}")
EMP_TOKEN=$(echo "$emp_login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ "$(echo "$emp_login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: employee login"; exit 1; fi
echo "Employee token obtained."

EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$INVITE_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')
if [ -z "$EMP_ID" ]; then echo "SETUP FAILED: employee id"; exit 1; fi

# Create assignment covering today (2026-06-27)
asgn_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: assignment ($(echo "$asgn_resp" | tail -n 1))"
    exit 1
fi
echo "Assignment created."
echo ""

CHECKIN_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/checkin"

# ── Test 1: Unauthenticated → 401 ────────────────────────────────────────────
echo "--- Test 1: No token → 401 ---"
run_test "Unauthenticated" 401 -s -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542}"
echo ""

# ── Test 2: Missing siteId → 400 ──────────────────────────────────────────────
echo "--- Test 2: Missing siteId → 400 ---"
run_test "Missing siteId" 400 -s -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":21.0285,"longitude":105.8542}'
echo ""

# ── Test 3: Missing latitude → 400 ───────────────────────────────────────────
echo "--- Test 3: Missing latitude → 400 ---"
run_test "Missing latitude" 400 -s -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"longitude\":105.8542}"
echo ""

# ── Test 4: Latitude out of range → 400 ──────────────────────────────────────
echo "--- Test 4: Latitude out of range → 400 ---"
run_test "Latitude out of range" 400 -s -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":999.0,\"longitude\":105.8542}"
echo ""

# ── Test 5: Non-existent site → 404 ──────────────────────────────────────────
echo "--- Test 5: Non-existent site → 404 ---"
run_test "Non-existent site" 404 -s -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"siteId":"00000000-0000-0000-0000-000000000000","latitude":21.0285,"longitude":105.8542}'
echo ""

# ── Test 6: Happy path — check-in inside geofence → 201 valid ────────────────
echo "--- Test 6: Check-in inside geofence → 201 status=valid ---"
ci_resp=$(curl -s -w "\n%{http_code}" -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"test-device\"}")
ci_body=$(echo "$ci_resp" | head -n -1)
ci_status=$(echo "$ci_resp" | tail -n 1)
if [ "$ci_status" -eq 201 ]; then
    checkin_status=$(echo "$ci_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
    inside=$(echo "$ci_body" | grep -o '"checkInInsideGeofence":[^,}]*' | cut -d: -f2 | tr -d ' ')
    CHECKIN_ID=$(echo "$ci_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ "$checkin_status" = "valid" ] && [ "$inside" = "true" ]; then
        echo "PASS: Check-in inside geofence (HTTP 201, status=$checkin_status, inside=$inside)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected status=valid and inside=true, got status=$checkin_status inside=$inside"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 201, got $ci_status — $ci_body"
    FAIL=$((FAIL + 1))
    CHECKIN_ID=""
fi
echo ""

# ── Test 7: Duplicate check-in (open session) → 409 ──────────────────────────
echo "--- Test 7: Second check-in while session open → 409 ---"
run_test "Duplicate check-in" 409 -s -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542}"
echo ""

# ── Test 8: Response includes all expected fields ─────────────────────────────
echo "--- Test 8: Response includes required fields ---"
if [ -n "$CHECKIN_ID" ]; then
    if echo "$ci_body" | grep -q '"checkInAt"' && \
       echo "$ci_body" | grep -q '"checkInLat"' && \
       echo "$ci_body" | grep -q '"gpsRiskScore"' && \
       echo "$ci_body" | grep -q '"checkOutAt"'; then
        echo "PASS: All required fields present in response"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing fields in response: $ci_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: No check-in ID (test 6 failed)"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 9: Check-in at site with no assignment → 404 ────────────────────────
echo "--- Setup: Create second site with no assignment ---"
s2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Remote Site","timezone":"UTC"}')
SITE2_ID=$(echo "$s2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "--- Test 9: Check-in at site with no assignment → 404 ---"
run_test "No assignment at site" 404 -s -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE2_ID\",\"latitude\":21.0285,\"longitude\":105.8542}"
echo ""

# ── Test 10: gpsRiskScore is low for accurate GPS inside geofence ─────────────
echo "--- Test 10: GPS risk score is low for accurate GPS inside geofence ---"
if [ -n "$CHECKIN_ID" ]; then
    risk=$(echo "$ci_body" | grep -o '"gpsRiskScore":[0-9.]*' | cut -d: -f2)
    if [ "$(echo "$risk < 0.3" | awk '{print ($1 < $3)}')" = "1" ]; then
        echo "PASS: GPS risk score is low for accurate GPS (score=$risk)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: GPS risk score should be < 0.3 for good GPS, got $risk"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: No check-in response to evaluate"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 11: Check-in outside geofence → 201 status=pending_review ───────────
echo "--- Setup: New employee for outside-geofence test ---"
INVITE_EMAIL2="outside.${TS}@example.com"
inv2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL2\",\"firstName\":\"Outside\",\"lastName\":\"Tester\"}")
if [ "$(echo "$inv2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation 2"; exit 1; fi

INV_TOKEN2=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL2' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN2\",\"password\":\"Employee@1234\"}"

emp2_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$INVITE_EMAIL2\",\"password\":\"Employee@1234\"}")
EMP2_TOKEN=$(echo "$emp2_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP2_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$INVITE_EMAIL2' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP2_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}"

echo "--- Test 11: Check-in far outside geofence → 201 status=pending_review ---"
# Coordinates far from the Hanoi geofence box (Ho Chi Minh City ~1700km away)
outside_resp=$(curl -s -w "\n%{http_code}" -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP2_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":10.7769,\"longitude\":106.7009,\"gpsAccuracy\":15.0}")
outside_body=$(echo "$outside_resp" | head -n -1)
outside_status=$(echo "$outside_resp" | tail -n 1)
if [ "$outside_status" -eq 201 ]; then
    ci_stat=$(echo "$outside_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
    inside_flag=$(echo "$outside_body" | grep -o '"checkInInsideGeofence":[^,}]*' | cut -d: -f2 | tr -d ' ')
    if [ "$ci_stat" = "pending_review" ] && [ "$inside_flag" = "false" ]; then
        echo "PASS: Outside geofence → pending_review (status=$ci_stat, inside=$inside_flag)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected pending_review + inside=false, got status=$ci_stat inside=$inside_flag"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 201, got $outside_status — $outside_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
