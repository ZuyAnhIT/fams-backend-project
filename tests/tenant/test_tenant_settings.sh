#!/usr/bin/env bash
# Tests for GET/PATCH /api/v1/tenants/{id}/settings (tenant display & format config)
# Usage: BASE_URL=http://localhost:8080 bash test_tenant_settings.sh

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

echo "=== Tenant Settings Tests ==="
echo "Target: $BASE_URL"
echo ""

# Login as platform admin
echo "--- Setup: Login as platform admin ---"
login_body=$(curl -s \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')

ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$ADMIN_TOKEN" ]; then
    echo "SETUP FAILED: Could not extract admin token"
    exit 1
fi
echo "Admin token obtained."

# Create a tenant for settings tests
TS=$(date +%s)
create_body=$(curl -s \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Settings Test Corp\",\"slug\":\"settings-test-$TS\"}")

TENANT_ID=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$TENANT_ID" ]; then
    echo "SETUP FAILED: Could not create tenant"
    exit 1
fi
echo "Test tenant created: id=$TENANT_ID"

# Register a regular user (non-owner)
reg_body=$(curl -s \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"regular_settings_$TS@fams.com\",\"password\":\"Regular@1234\",\"displayName\":\"Regular\"}")
REGULAR_TOKEN=$(echo "$reg_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Regular user token obtained."
echo ""

# Test 1: GET settings — defaults created on first access
echo "--- Test 1: GET settings returns defaults ---"
get_response=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/settings" \
    -H "Authorization: Bearer $ADMIN_TOKEN")

get_body=$(echo "$get_response" | head -n -1)
get_status=$(echo "$get_response" | tail -n 1)

if [ "$get_status" -eq 200 ]; then
    date_fmt=$(echo "$get_body" | grep -o '"dateFormat":"[^"]*"' | cut -d'"' -f4)
    time_fmt=$(echo "$get_body" | grep -o '"timeFormat":"[^"]*"' | cut -d'"' -f4)
    if [ "$date_fmt" = "DD/MM/YYYY" ] && [ "$time_fmt" = "HH:mm" ]; then
        echo "PASS: GET defaults (HTTP 200, dateFormat=$date_fmt timeFormat=$time_fmt)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: GET defaults — unexpected values: dateFormat=$date_fmt timeFormat=$time_fmt"
        echo "Body: $get_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: GET defaults — expected HTTP 200, got HTTP $get_status"
    echo "Body: $get_body"
    FAIL=$((FAIL + 1))
fi

# Test 2: PATCH settings — update date format and brand colors
echo ""
echo "--- Test 2: PATCH settings (update dateFormat and brand colors) ---"
patch_response=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/settings" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"dateFormat":"MM/DD/YYYY","timeFormat":"h:mm a","brandPrimaryColor":"#3B82F6","brandSecondaryColor":"#10B981","brandAccentColor":"#F59E0B"}')

patch_body=$(echo "$patch_response" | head -n -1)
patch_status=$(echo "$patch_response" | tail -n 1)

if [ "$patch_status" -eq 200 ]; then
    date_fmt=$(echo "$patch_body" | grep -o '"dateFormat":"[^"]*"' | cut -d'"' -f4)
    primary=$(echo "$patch_body" | grep -o '"brandPrimaryColor":"[^"]*"' | cut -d'"' -f4)
    if [ "$date_fmt" = "MM/DD/YYYY" ] && [ "$primary" = "#3B82F6" ]; then
        echo "PASS: PATCH settings (HTTP 200, dateFormat=$date_fmt primary=$primary)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: PATCH settings — dateFormat=$date_fmt primary=$primary"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: PATCH settings — expected HTTP 200, got HTTP $patch_status"
    echo "Body: $patch_body"
    FAIL=$((FAIL + 1))
fi

# Test 3: GET after update reflects new values
echo ""
echo "--- Test 3: GET after update reflects saved values ---"
get2_response=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/settings" \
    -H "Authorization: Bearer $ADMIN_TOKEN")

get2_body=$(echo "$get2_response" | head -n -1)
get2_status=$(echo "$get2_response" | tail -n 1)

if [ "$get2_status" -eq 200 ]; then
    date_fmt2=$(echo "$get2_body" | grep -o '"dateFormat":"[^"]*"' | cut -d'"' -f4)
    if [ "$date_fmt2" = "MM/DD/YYYY" ]; then
        echo "PASS: GET reflects update (HTTP 200, dateFormat=$date_fmt2)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: GET reflects update — dateFormat=$date_fmt2"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: GET reflects update — expected HTTP 200, got HTTP $get2_status"
    FAIL=$((FAIL + 1))
fi

# Test 4: Invalid hex color → 400
echo ""
echo "--- Test 4: Invalid brand color format ---"
run_test "Invalid hex color" 400 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/settings" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"brandPrimaryColor":"not-a-color"}'

# Test 5: Tenant not found → 404
echo ""
echo "--- Test 5: Tenant not found ---"
FAKE_ID="00000000-0000-0000-0000-000000000000"
run_test "Tenant not found (GET)" 404 \
    -X GET "$BASE_URL/api/v1/tenants/$FAKE_ID/settings" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

run_test "Tenant not found (PATCH)" 404 \
    -X PATCH "$BASE_URL/api/v1/tenants/$FAKE_ID/settings" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"dateFormat":"YYYY-MM-DD"}'

# Test 6: Unauthenticated → 401
echo ""
echo "--- Test 6: Unauthenticated ---"
run_test "Unauthenticated GET" 401 \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/settings"

run_test "Unauthenticated PATCH" 401 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/settings" \
    -H "Content-Type: application/json" \
    -d '{"dateFormat":"YYYY-MM-DD"}'

# Test 7: Non-owner regular user → 403
echo ""
echo "--- Test 7: Forbidden (regular user is not tenant owner) ---"
run_test "Non-owner forbidden GET" 403 \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/settings" \
    -H "Authorization: Bearer $REGULAR_TOKEN"

run_test "Non-owner forbidden PATCH" 403 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/settings" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $REGULAR_TOKEN" \
    -d '{"dateFormat":"YYYY-MM-DD"}'

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
