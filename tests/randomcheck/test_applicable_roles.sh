#!/usr/bin/env bash
# Tests for applicable-roles configuration (task 95)
# PUT /api/v1/tenants/{tenantId}/random-check-configs/{configId}/applicable-roles
# Usage: BASE_URL=http://localhost:8080 bash test_applicable_roles.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1"
    local expected_status="$2"
    local curl_args=("${@:3}")
    local actual_status
    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${curl_args[@]}")
    if [ "$actual_status" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

check_roles_value() {
    local label="$1"
    local config_id="$2"
    local expected_count="$3"
    local resp body roles_json count status
    resp=$(curl -s -w "\n%{http_code}" \
        -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$config_id" \
        -H "Authorization: Bearer $ADMIN_TOKEN")
    status=$(echo "$resp" | tail -n 1)
    body=$(echo "$resp" | head -n -1)
    # Count elements inside applicableRoles array
    roles_json=$(echo "$body" | grep -o '"applicableRoles":\[[^]]*\]' | head -1)
    roles_inner=$(echo "$roles_json" | sed 's/"applicableRoles":\[//;s/\]//')
    if [ -z "$roles_inner" ] || [ "$roles_inner" = "[]" ]; then
        count=0
    else
        count=$(echo "$roles_inner" | grep -o '"[^"]*"' | wc -l | tr -d ' ')
    fi
    if [ "$status" -eq 200 ] && [ "$count" -eq "$expected_count" ]; then
        echo "PASS: $label — $count role(s) in applicableRoles"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $label — expected $expected_count roles, got $count (HTTP $status)"
        echo "  Body fragment: $roles_json"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Applicable Roles Configuration Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup: Login ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then echo "SETUP FAILED: login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Token obtained."

TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Roles Corp ${TS}\",\"slug\":\"roles-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant: $TENANT_ID"

cfg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 1,
      "minIntervalMinutes": 0,
      "allowedStartTime": "08:00:00",
      "allowedEndTime": "17:00:00",
      "checkMode": "location_only",
      "applicableRoles": ["supervisor", "employee"],
      "responseWindowSeconds": 300
    }')
if [ "$(echo "$cfg_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: config"; exit 1; fi
CONFIG_ID=$(echo "$cfg_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Config: $CONFIG_ID"
echo ""

ROLES_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$CONFIG_ID/applicable-roles"

# ── Initial state ─────────────────────────────────────────────────────────────
echo "--- Test 1: Config created with 2 roles ---"
check_roles_value "Initial roles count is 2" "$CONFIG_ID" 2

echo ""
echo "--- Test 2: Replace roles with a single role ---"
upd_resp=$(curl -s -w "\n%{http_code}" \
    -X PUT "$ROLES_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"applicableRoles": ["employee"]}')
upd_status=$(echo "$upd_resp" | tail -n 1)
if [ "$upd_status" -eq 200 ]; then
    echo "PASS: Replace roles to [employee] (HTTP 200)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Replace roles — expected 200, got $upd_status"
    FAIL=$((FAIL + 1))
fi
check_roles_value "Roles updated to 1" "$CONFIG_ID" 1

echo ""
echo "--- Test 3: Add multiple roles ---"
run_test "Set 3 roles returns 200" 200 \
    -X PUT "$ROLES_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"applicableRoles": ["supervisor", "employee", "guard"]}'
check_roles_value "Roles updated to 3" "$CONFIG_ID" 3

echo ""
echo "--- Test 4: Set empty list (apply to all roles) ---"
run_test "Empty applicableRoles list returns 200" 200 \
    -X PUT "$ROLES_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"applicableRoles": []}'
check_roles_value "Roles updated to 0 (all roles)" "$CONFIG_ID" 0

echo ""
echo "--- Test 5: Restore roles after empty ---"
run_test "Restore roles after empty list returns 200" 200 \
    -X PUT "$ROLES_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"applicableRoles": ["supervisor"]}'
check_roles_value "Roles restored to 1" "$CONFIG_ID" 1

echo ""
echo "--- Test 6: Null applicableRoles — should return 400 ---"
run_test "Null applicableRoles returns 400" 400 \
    -X PUT "$ROLES_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"applicableRoles": null}'

echo ""
echo "--- Test 7: Missing applicableRoles field — should return 400 ---"
run_test "Missing applicableRoles field returns 400" 400 \
    -X PUT "$ROLES_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{}'

echo ""
echo "--- Test 8: Blank string in roles list — should return 400 ---"
run_test "Blank role name in list returns 400" 400 \
    -X PUT "$ROLES_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"applicableRoles": ["supervisor", ""]}'

echo ""
echo "--- Test 9: Unauthorized — should return 401 ---"
run_test "No auth token returns 401" 401 \
    -X PUT "$ROLES_URL" \
    -H "Content-Type: application/json" \
    -d '{"applicableRoles": ["employee"]}'

echo ""
echo "--- Test 10: Unknown config ID — should return 404 ---"
FAKE_ID="00000000-0000-0000-0000-000000000000"
run_test "Unknown configId returns 404" 404 \
    -X PUT "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$FAKE_ID/applicable-roles" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"applicableRoles": ["employee"]}'

echo ""
echo "--- Test 11: Updating roles does not change other fields ---"
get_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$CONFIG_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
get_body=$(echo "$get_resp" | head -n -1)
checks=$(echo "$get_body" | grep -o '"checksPerShift":[0-9]*' | cut -d: -f2)
mode=$(echo "$get_body" | grep -o '"checkMode":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ "$checks" = "1" ] && [ "$mode" = "location_only" ]; then
    echo "PASS: Other fields unchanged after roles update"
    PASS=$((PASS + 1))
else
    echo "FAIL: Fields changed unexpectedly — checksPerShift=$checks checkMode=$mode"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 12: Create config with roles from the start and verify response ---"
t2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Roles2 Corp ${TS}\",\"slug\":\"roles2-corp-${TS}\"}")
T2_ID=$(echo "$t2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
cfg2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$T2_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 1,
      "minIntervalMinutes": 0,
      "allowedStartTime": "08:00:00",
      "allowedEndTime": "17:00:00",
      "checkMode": "location_only",
      "applicableRoles": ["foreman", "worker", "security"],
      "responseWindowSeconds": 120
    }')
cfg2_status=$(echo "$cfg2_resp" | tail -n 1)
cfg2_body=$(echo "$cfg2_resp" | head -n -1)
roles_json=$(echo "$cfg2_body" | grep -o '"applicableRoles":\[[^]]*\]' | head -1)
count=$(echo "$roles_json" | grep -o '"[^"]*"' | grep -v 'applicableRoles' | wc -l | tr -d ' ')
if [ "$cfg2_status" -eq 201 ] && [ "$count" -eq 3 ]; then
    echo "PASS: Config created with 3 roles, response contains all 3"
    PASS=$((PASS + 1))
else
    echo "FAIL: Create with 3 roles — HTTP $cfg2_status, roles count=$count"
    echo "  Roles fragment: $roles_json"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "=============================="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo "=============================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
