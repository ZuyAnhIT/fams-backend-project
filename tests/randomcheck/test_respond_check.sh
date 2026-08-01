#!/usr/bin/env bash
# Tests for employee GPS response to a random check (task 102)
# POST /api/v1/tenants/{tenantId}/scheduled-checks/{checkId}/respond
#
# Covers:
#   - Response accepted when GPS is inside the site geofence  → outcome=pass
#   - Response accepted when GPS is outside the geofence      → outcome=fail, failureReason=location_mismatch
#   - Response accepted when no geofence is configured        → outcome=pass (permissive)
#   - 401 when no auth token is sent
#   - 404 when check does not belong to the calling employee
#   - 400 when latitude/longitude are missing
#   - 400 when attempting to respond to an already-responded check
#
# Usage:
#   BASE_URL=http://localhost:8080 bash test_respond_check.sh
#   ACCESS_TOKEN and EMPLOYEE_TOKEN are optional; the script self-provisions everything.
#
# Requirements: curl only (no docker exec, redis-cli, or other tooling).

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

# ─── Helpers ──────────────────────────────────────────────────────────────────

run_test() {
    local name="$1"
    local expected_status="$2"
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

check_val() {
    local name="$1" actual="$2" expected="$3"
    if [ "$actual" = "$expected" ]; then
        echo "PASS: $name (=$expected)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected '$expected', got '$actual'"
        FAIL=$((FAIL + 1))
    fi
}

check_contains() {
    local name="$1" haystack="$2" needle="$3"
    if echo "$haystack" | grep -qF "$needle"; then
        echo "PASS: $name (found '$needle')"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — '$needle' not found in: $haystack"
        FAIL=$((FAIL + 1))
    fi
}

# Extract first JSON string value for a given key from a single-line JSON blob.
# Usage: json_val <key> <json>
json_val() {
    local key="$1" json="$2"
    echo "$json" | grep -o "\"${key}\":\"[^\"]*\"" | head -1 | cut -d'"' -f4
}

echo "=== GPS Respond-to-Random-Check Tests (task 102) ==="
echo "Target: $BASE_URL"
echo ""

# ─── Setup ────────────────────────────────────────────────────────────────────
echo "--- Setup ---"

# Platform admin login
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

# Tenant
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"GPS Respond Corp ${TS}\",\"slug\":\"gps-respond-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(json_val "id" "$(echo "$t_resp" | head -n -1)")

# Site
s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"GPS Site\",\"code\":\"GS-${TS}\",\"address\":\"1 GPS St\",\"timezone\":\"UTC\"}")
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(json_val "id" "$(echo "$s_resp" | head -n -1)")

# Shift
sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(json_val "id" "$(echo "$sh_resp" | head -n -1)")

# Employee — invitation token is returned in the response body (curl-only)
EMP_EMAIL="gps.respond.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"GPS\",\"lastName\":\"Emp\"}")
if [ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation"; exit 1; fi
INV_TOKEN=$(json_val "token" "$(echo "$inv_resp" | head -n -1)")
if [ -z "$INV_TOKEN" ]; then echo "SETUP FAILED: invitation token missing from response"; exit 1; fi

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

EMP_TOKEN_RAW=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$EMP_EMAIL\",\"password\":\"Employee@1234\"}")
if [ "$(echo "$EMP_TOKEN_RAW" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: employee login"; exit 1; fi
EMP_TOKEN=$(echo "$EMP_TOKEN_RAW" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

# Get EMP_ID via employee list API
emp_list=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/employees?search=$EMP_EMAIL&size=5")
EMP_ID=$(json_val "id" "$emp_list")
if [ -z "$EMP_ID" ]; then echo "SETUP FAILED: employee ID not found"; exit 1; fi

# Assignment
asgn_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2099-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi

# Random check config (location_only, 1-hour response window)
cfg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 1,
      "minIntervalMinutes": 60,
      "allowedStartTime": "09:00:00",
      "allowedEndTime": "16:00:00",
      "checkMode": "location_only",
      "applicableRoles": [],
      "responseWindowSeconds": 3600
    }')
if [ "$(echo "$cfg_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: random check config"; exit 1; fi

# ── Define a small geofence polygon around a known worksite location ──────────
# We use a ~100m square near Ho Chi Minh City at approx lon=106.660, lat=10.762.
# A point INSIDE:  lat=10.7626,  lon=106.6601
# A point OUTSIDE: lat=10.7700,  lon=106.6700  (several km away)
#
# Coordinates are [longitude, latitude] pairs (GeoJSON order).
# The polygon is a tight box; bufferMeters=50 to give some tolerance.
GEO_RESP=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/geofences" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "coordinates": [
        [106.6598, 10.7622],
        [106.6604, 10.7622],
        [106.6604, 10.7630],
        [106.6598, 10.7630],
        [106.6598, 10.7622]
      ],
      "bufferMeters": 50
    }')
if [ "$(echo "$GEO_RESP" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: geofence creation ($(echo "$GEO_RESP" | tail -n 1))"
    echo "Body: $(echo "$GEO_RESP" | head -n -1)"
    exit 1
fi
GEOFENCE_ID=$(json_val "id" "$(echo "$GEO_RESP" | head -n -1)")

BASE_CHECKS="$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks"

# Helper: trigger a fresh manual check (status=sent, 1-hour window)
dispatch_check() {
    local ch
    ch=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_CHECKS/manual" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"siteId\":\"$SITE_ID\",\"employeeId\":\"$EMP_ID\",\"reason\":\"test manual check\"}")
    local status
    status=$(echo "$ch" | tail -n 1)
    if [ "$status" -ne 201 ]; then
        echo "SETUP FAILED: manual check dispatch (HTTP $status)" >&2
        echo "Body: $(echo "$ch" | head -n -1)" >&2
        exit 1
    fi
    echo "$ch" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4
}

echo "Setup complete."
echo "  TENANT=$TENANT_ID"
echo "  SITE=$SITE_ID"
echo "  EMP=$EMP_ID"
echo "  GEOFENCE=$GEOFENCE_ID"
echo ""

# ─── Test 1: GPS INSIDE geofence → HTTP 200, outcome=pass ────────────────────
echo "--- Test 1: GPS inside geofence returns 200 with outcome=pass ---"
CHECK1=$(dispatch_check)
echo "  check=$CHECK1"
resp1=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_CHECKS/$CHECK1/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.7626,"longitude":106.6601}')
status1=$(echo "$resp1" | tail -n 1)
body1=$(echo "$resp1" | head -n -1)
if [ "$status1" -eq 200 ]; then
    echo "PASS: inside-geofence response returns 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: inside-geofence — expected 200, got $status1"
    echo "  Body: $body1"
    FAIL=$((FAIL + 1))
fi

outcome1=$(json_val "outcome" "$body1")
check_val "inside-geofence outcome=pass" "$outcome1" "pass"

location_verified1=$(echo "$body1" | grep -o '"locationVerified":[a-z]*' | head -1 | cut -d: -f2)
check_val "inside-geofence locationVerified=true" "$location_verified1" "true"

failure_reason1=$(echo "$body1" | grep -o '"failureReason":null' | head -1)
check_val "inside-geofence failureReason=null" "$failure_reason1" '"failureReason":null'

# ─── Test 2: GPS OUTSIDE geofence → HTTP 200, outcome=fail ───────────────────
echo ""
echo "--- Test 2: GPS outside geofence returns 200 with outcome=fail ---"
CHECK2=$(dispatch_check)
echo "  check=$CHECK2"
resp2=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_CHECKS/$CHECK2/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.7700,"longitude":106.6700}')
status2=$(echo "$resp2" | tail -n 1)
body2=$(echo "$resp2" | head -n -1)
if [ "$status2" -eq 200 ]; then
    echo "PASS: outside-geofence response returns 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: outside-geofence — expected 200, got $status2"
    echo "  Body: $body2"
    FAIL=$((FAIL + 1))
fi

outcome2=$(json_val "outcome" "$body2")
check_val "outside-geofence outcome=fail" "$outcome2" "fail"

location_verified2=$(echo "$body2" | grep -o '"locationVerified":[a-z]*' | head -1 | cut -d: -f2)
check_val "outside-geofence locationVerified=false" "$location_verified2" "false"

check_contains "outside-geofence failureReason contains location_mismatch" "$body2" "location_mismatch"

# ─── Test 3: Response body has required fields ────────────────────────────────
echo ""
echo "--- Test 3: Response body has all required GPS-check fields ---"
check_contains "body has scheduledCheckId" "$body2" '"scheduledCheckId"'
check_contains "body has latitude" "$body2" '"latitude"'
check_contains "body has longitude" "$body2" '"longitude"'
check_contains "body has respondedAt" "$body2" '"respondedAt"'
check_contains "body has outcome" "$body2" '"outcome"'

# ─── Test 4: No-geofence tenant → GPS always passes ──────────────────────────
# Because the plan limits each tenant to 1 site, we create a fresh tenant with
# no geofence configured to verify the permissive fallback.
echo ""
echo "--- Test 4: Site without geofence → response accepted, outcome=pass ---"

TS2=$((TS + 1))
t2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"NoGeo Corp ${TS2}\",\"slug\":\"nogeo-${TS2}\",\"ownerEmail\":\"admin@fams.com\"}")
if [ "$(echo "$t2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: no-geo tenant"; exit 1; fi
TENANT2_ID=$(json_val "id" "$(echo "$t2_resp" | head -n -1)")

s2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT2_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"NoGeo Site\",\"code\":\"NG-${TS2}\",\"address\":\"2 GPS St\",\"timezone\":\"UTC\"}")
if [ "$(echo "$s2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: no-geo site"; exit 1; fi
SITE2_ID=$(json_val "id" "$(echo "$s2_resp" | head -n -1)")

sh2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT2_ID/sites/$SITE2_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: no-geo shift"; exit 1; fi
SHIFT2_ID=$(json_val "id" "$(echo "$sh2_resp" | head -n -1)")

# Invite the same employee email to the new tenant
EMP2_EMAIL="nogeo.emp.${TS2}@example.com"
inv2_ng_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT2_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP2_EMAIL\",\"firstName\":\"NoGeo\",\"lastName\":\"Emp\"}")
if [ "$(echo "$inv2_ng_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: no-geo invitation"; exit 1; fi
INV2_NG_TOKEN=$(json_val "token" "$(echo "$inv2_ng_resp" | head -n -1)")
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV2_NG_TOKEN\",\"password\":\"Employee@1234\"}"
EMP2_NG_LOGIN=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$EMP2_EMAIL\",\"password\":\"Employee@1234\"}")
EMP2_NG_TOKEN=$(echo "$EMP2_NG_LOGIN" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
emp2_list=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/tenants/$TENANT2_ID/employees?search=$EMP2_EMAIL&size=5")
EMP2_ID=$(json_val "id" "$emp2_list")

asgn2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT2_ID/sites/$SITE2_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP2_ID\",\"shiftId\":\"$SHIFT2_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2099-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: no-geo assignment"; exit 1; fi

# Tenant-default config (no geofence will be created for this tenant)
cfg2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT2_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 1,
      "minIntervalMinutes": 60,
      "allowedStartTime": "09:00:00",
      "allowedEndTime": "16:00:00",
      "checkMode": "location_only",
      "applicableRoles": [],
      "responseWindowSeconds": 3600
    }')
if [ "$(echo "$cfg2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: no-geo config"; exit 1; fi

BASE_CHECKS2="$BASE_URL/api/v1/tenants/$TENANT2_ID/scheduled-checks"
CHECK3_RAW=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_CHECKS2/manual" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"siteId\":\"$SITE2_ID\",\"employeeId\":\"$EMP2_ID\",\"reason\":\"test manual check\"}")
if [ "$(echo "$CHECK3_RAW" | tail -n 1)" -ne 201 ]; then
    echo "SKIP Test 4: manual check for no-geo site failed ($(echo "$CHECK3_RAW" | tail -n 1))"
else
    CHECK3_ID=$(echo "$CHECK3_RAW" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    resp3=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_CHECKS2/$CHECK3_ID/respond" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $EMP2_NG_TOKEN" \
        -d '{"latitude":51.5074,"longitude":-0.1278}')
    status3=$(echo "$resp3" | tail -n 1)
    body3=$(echo "$resp3" | head -n -1)
    if [ "$status3" -eq 200 ]; then
        echo "PASS: no-geofence response returns 200"
        PASS=$((PASS + 1))
    else
        echo "FAIL: no-geofence — expected 200, got $status3"
        echo "  Body: $body3"
        FAIL=$((FAIL + 1))
    fi
    outcome3=$(json_val "outcome" "$body3")
    check_val "no-geofence outcome=pass (permissive)" "$outcome3" "pass"
fi

# ─── Test 5: Missing latitude returns 400 ────────────────────────────────────
echo ""
echo "--- Test 5: Missing latitude in request returns 400 ---"
CHECK4=$(dispatch_check)
run_test "missing latitude returns 400" 400 \
    -X POST "$BASE_CHECKS/$CHECK4/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"longitude":106.6601}'

# ─── Test 6: Missing longitude returns 400 ───────────────────────────────────
echo ""
echo "--- Test 6: Missing longitude in request returns 400 ---"
CHECK5=$(dispatch_check)
run_test "missing longitude returns 400" 400 \
    -X POST "$BASE_CHECKS/$CHECK5/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.7626}'

# ─── Test 7: No auth token returns 401 ───────────────────────────────────────
echo ""
echo "--- Test 7: No auth token returns 401 ---"
CHECK6=$(dispatch_check)
run_test "no token returns 401" 401 \
    -X POST "$BASE_CHECKS/$CHECK6/respond" \
    -H "Content-Type: application/json" \
    -d '{"latitude":10.7626,"longitude":106.6601}'

# ─── Test 8: Employee cannot respond to another employee's check ──────────────
echo ""
echo "--- Test 8: Employee cannot respond to another employee's check (returns 404) ---"

EMP2_EMAIL="gps.other.${TS}@example.com"
inv2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP2_EMAIL\",\"firstName\":\"Other\",\"lastName\":\"Emp\"}")
if [ "$(echo "$inv2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: emp2 invitation"; exit 1; fi
INV2_TOKEN=$(json_val "token" "$(echo "$inv2_resp" | head -n -1)")
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV2_TOKEN\",\"password\":\"Employee@1234\"}"
emp2_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$EMP2_EMAIL\",\"password\":\"Employee@1234\"}")
EMP2_TOKEN=$(echo "$emp2_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

CHECK7=$(dispatch_check)   # This check is assigned to EMP (employee 1)
run_test "other-employee check returns 404" 404 \
    -X POST "$BASE_CHECKS/$CHECK7/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP2_TOKEN" \
    -d '{"latitude":10.7626,"longitude":106.6601}'

# ─── Test 9: Responding twice to the same check returns 400 ──────────────────
echo ""
echo "--- Test 9: Duplicate response to already-responded check returns 400 ---"
CHECK8=$(dispatch_check)
# First response (should succeed)
curl -s -o /dev/null -X POST "$BASE_CHECKS/$CHECK8/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.7626,"longitude":106.6601}'
# Second response (should fail)
run_test "duplicate response returns 400" 400 \
    -X POST "$BASE_CHECKS/$CHECK8/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.7626,"longitude":106.6601}'

# ─── Test 10: Non-existent check returns 404 ─────────────────────────────────
echo ""
echo "--- Test 10: Responding to non-existent check returns 404 ---"
FAKE_ID="00000000-0000-0000-0000-000000000099"
run_test "non-existent check returns 404" 404 \
    -X POST "$BASE_CHECKS/$FAKE_ID/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.7626,"longitude":106.6601}'

# ─── Test 11: accuracyMeters is accepted as an optional field ─────────────────
echo ""
echo "--- Test 11: Optional accuracyMeters field is accepted ---"
CHECK9=$(dispatch_check)
resp11=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_CHECKS/$CHECK9/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.7626,"longitude":106.6601,"accuracyMeters":12.5}')
status11=$(echo "$resp11" | tail -n 1)
body11=$(echo "$resp11" | head -n -1)
if [ "$status11" -eq 200 ]; then
    echo "PASS: accuracyMeters accepted (200)"
    PASS=$((PASS + 1))
else
    echo "FAIL: accuracyMeters field rejected — expected 200, got $status11"
    echo "  Body: $body11"
    FAIL=$((FAIL + 1))
fi
# Verify accuracyMeters is echoed back in the response
check_contains "accuracyMeters echoed in response" "$body11" '"accuracyMeters"'

# ─── Results ──────────────────────────────────────────────────────────────────
echo ""
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
