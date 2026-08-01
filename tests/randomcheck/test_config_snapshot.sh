#!/usr/bin/env bash
# Tests for config_snapshot stored in scheduled_checks (task 97)
# Verifies the snapshot captures config values at generation time and
# remains unchanged when the config is later updated.
# Usage: BASE_URL=http://localhost:8080 bash test_config_snapshot.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

check_eq() {
    local label="$1" expected="$2" actual="$3"
    if [ "$actual" = "$expected" ]; then
        echo "PASS: $label (got '$actual')"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $label — expected '$expected', got '$actual'"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Config Snapshot Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup: Login ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Token obtained."

TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Snapshot Corp ${TS}\",\"slug\":\"snapshot-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant: $TENANT_ID"

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Snap Site ${TS}\",\"code\":\"SS-${TS}\",\"address\":\"1 St\",\"timezone\":\"UTC\"}")
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Site: $SITE_ID"

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

INVITE_EMAIL="snap.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Snap\",\"lastName\":\"Emp\"}")
if [ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation"; exit 1; fi
INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"
EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$INVITE_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

asgn_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi
ASGN_ID=$(echo "$asgn_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Create config with known, distinctive values that we can recognise in the snapshot
cfg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 3,
      "minIntervalMinutes": 45,
      "allowedStartTime": "09:00:00",
      "allowedEndTime": "16:00:00",
      "checkMode": "location_only",
      "applicableRoles": ["worker", "supervisor"],
      "responseWindowSeconds": 180
    }')
if [ "$(echo "$cfg_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: config"; exit 1; fi
CFG_ID=$(echo "$cfg_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Config: $CFG_ID"

# Generate for a future date to avoid collision with other test runs
GEN_DATE="2026-09-15"
gen_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/generate" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"date\":\"$GEN_DATE\"}")
if [ "$(echo "$gen_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: generation"; exit 1; fi
echo "Checks generated for $GEN_DATE."
echo ""

# ── Read snapshot from DB ─────────────────────────────────────────────────────
db_field() {
    local sql="$1"
    docker exec fams-postgres psql -U fams_user -d fams_db -t -c "$sql" | tr -d ' \n'
}

echo "Snapshot fields from DB:"
echo ""

# ── Test 1–8: Snapshot contains correct values ────────────────────────────────
echo "--- Tests 1–8: Snapshot field values match config at generation time ---"

WHERE="assignment_id='$ASGN_ID' AND check_date='$GEN_DATE' AND check_index=1 AND deleted_at IS NULL"

snap_config_id=$(db_field "SELECT config_snapshot->>'configId' FROM scheduled_checks WHERE $WHERE LIMIT 1;")
check_eq "configId matches" "$CFG_ID" "$snap_config_id"

snap_mode=$(db_field "SELECT config_snapshot->>'checkMode' FROM scheduled_checks WHERE $WHERE LIMIT 1;")
check_eq "checkMode is 'location_only'" "location_only" "$snap_mode"

snap_checks=$(db_field "SELECT (config_snapshot->>'checksPerShift')::int FROM scheduled_checks WHERE $WHERE LIMIT 1;")
check_eq "checksPerShift is 3" "3" "$snap_checks"

snap_interval=$(db_field "SELECT (config_snapshot->>'minIntervalMinutes')::int FROM scheduled_checks WHERE $WHERE LIMIT 1;")
check_eq "minIntervalMinutes is 45" "45" "$snap_interval"

snap_start=$(db_field "SELECT config_snapshot->>'allowedStartTime' FROM scheduled_checks WHERE $WHERE LIMIT 1;")
check_eq "allowedStartTime is 09:00" "09:00" "$snap_start"

snap_end=$(db_field "SELECT config_snapshot->>'allowedEndTime' FROM scheduled_checks WHERE $WHERE LIMIT 1;")
check_eq "allowedEndTime is 16:00" "16:00" "$snap_end"

snap_window=$(db_field "SELECT (config_snapshot->>'responseWindowSeconds')::int FROM scheduled_checks WHERE $WHERE LIMIT 1;")
check_eq "responseWindowSeconds is 180" "180" "$snap_window"

# applicableRoles array should contain both worker and supervisor
roles_has_worker=$(db_field "SELECT (config_snapshot->'applicableRoles') ? 'worker' FROM scheduled_checks WHERE $WHERE LIMIT 1;")
roles_has_supervisor=$(db_field "SELECT (config_snapshot->'applicableRoles') ? 'supervisor' FROM scheduled_checks WHERE $WHERE LIMIT 1;")
if [ "$roles_has_worker" = "t" ] && [ "$roles_has_supervisor" = "t" ]; then
    echo "PASS: applicableRoles contains 'worker' and 'supervisor'"
    PASS=$((PASS + 1))
else
    echo "FAIL: applicableRoles missing expected values (worker=$roles_has_worker supervisor=$roles_has_supervisor)"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 9: configSnapshot appears in list API response ---"
list_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks?date=$GEN_DATE" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list_body=$(echo "$list_resp" | head -n -1)
if echo "$list_body" | grep -q '"configSnapshot"'; then
    echo "PASS: configSnapshot field present in list API response"
    PASS=$((PASS + 1))
else
    echo "FAIL: configSnapshot field missing from list API response"
    FAIL=$((FAIL + 1))
fi
# Also verify it contains the config id we used
if echo "$list_body" | grep -q "\"configId\":\"$CFG_ID\""; then
    echo "PASS: configId in snapshot matches in API response"
    PASS=$((PASS + 1))
else
    echo "FAIL: configId not found in API response snapshot"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Tests 10–13: Snapshot is frozen after config mutation ---"
# Now update the config with completely different values
curl -s -o /dev/null \
    -X PUT "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$CFG_ID" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 1,
      "minIntervalMinutes": 0,
      "checkMode": "location_face_liveness",
      "applicableRoles": ["manager"],
      "responseWindowSeconds": 600
    }'

# Read the snapshot again — it must still hold the original values
snap2_mode=$(db_field "SELECT config_snapshot->>'checkMode' FROM scheduled_checks WHERE $WHERE LIMIT 1;")
check_eq "Snapshot checkMode still 'location_only' after config update" "location_only" "$snap2_mode"

snap2_checks=$(db_field "SELECT (config_snapshot->>'checksPerShift')::int FROM scheduled_checks WHERE $WHERE LIMIT 1;")
check_eq "Snapshot checksPerShift still 3 after config update" "3" "$snap2_checks"

snap2_window=$(db_field "SELECT (config_snapshot->>'responseWindowSeconds')::int FROM scheduled_checks WHERE $WHERE LIMIT 1;")
check_eq "Snapshot responseWindowSeconds still 180 after config update" "180" "$snap2_window"

still_worker=$(db_field "SELECT (config_snapshot->'applicableRoles') ? 'worker' FROM scheduled_checks WHERE $WHERE LIMIT 1;")
if [ "$still_worker" = "t" ]; then
    echo "PASS: Snapshot applicableRoles still contains 'worker' after config update"
    PASS=$((PASS + 1))
else
    echo "FAIL: Snapshot applicableRoles changed after config update"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 14: New generation (different date) uses updated config values ---"
GEN_DATE2="2026-09-16"
gen2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/generate" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"date\":\"$GEN_DATE2\"}")
# Config now has applicableRoles=["manager"] and employee role is "worker", so 0 checks expected
created2=$(echo "$gen2_resp" | head -n -1 | grep -o '"created":[0-9]*' | cut -d: -f2)
check_eq "New generation uses updated config (role 'worker' filtered out → 0 checks)" "0" "${created2:-0}"

echo ""
echo "--- Test 15: All 3 checks for $GEN_DATE have identical snapshots ---"
distinct_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(DISTINCT config_snapshot::text) FROM scheduled_checks WHERE assignment_id='$ASGN_ID' AND check_date='$GEN_DATE' AND deleted_at IS NULL;" \
    | tr -d ' \n')
check_eq "All checks for same generation have identical snapshots (1 distinct)" "1" "$distinct_count"

echo ""
echo "=============================="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo "=============================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
