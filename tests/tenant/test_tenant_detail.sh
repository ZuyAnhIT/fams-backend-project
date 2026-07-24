#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{id}/detail
# Usage: BASE_URL=http://localhost:8080 bash test_tenant_detail.sh

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

run_test_with_body() {
    local name="$1"
    local expected_status="$2"
    local check_field="$3"
    local curl_args=("${@:4}")

    response=$(curl -s -w "\n%{http_code}" "${curl_args[@]}")
    actual_status=$(echo "$response" | tail -n 1)
    body=$(echo "$response" | head -n -1)

    if [ "$actual_status" -eq "$expected_status" ]; then
        if [ -n "$check_field" ] && ! echo "$body" | grep -q "$check_field"; then
            echo "FAIL: $name — HTTP $actual_status but missing field: $check_field"
            FAIL=$((FAIL + 1))
        else
            echo "PASS: $name (HTTP $actual_status)"
            PASS=$((PASS + 1))
        fi
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Tenant Detail Tests ==="
echo "Target: $BASE_URL"
echo ""

# --- Setup: Login as platform admin ---
echo "--- Setup: Login as platform admin ---"
login_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_response" | head -n -1)
login_status=$(echo "$login_response" | tail -n 1)

if [ "$login_status" -ne 200 ]; then
    echo "SETUP FAILED: Could not login as admin (HTTP $login_status)"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$ADMIN_TOKEN" ]; then
    echo "SETUP FAILED: Could not extract admin token"
    exit 1
fi
echo "Admin token obtained."

# --- Setup: Register an existing user to be the tenant's owner (now required — see
# TenantService.createTenant, platform-admin-provisioned tenants assign a direct owner) ---
TS=$(date +%s)
OWNER_EMAIL="tenant_detail_owner_${TS}@fams.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/register" -H "Content-Type: application/json" \
    -d "{\"email\":\"$OWNER_EMAIL\",\"password\":\"TestPass1\",\"displayName\":\"Detail Owner\"}"
docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
    "UPDATE users SET email_verified = true WHERE email = '$OWNER_EMAIL';" > /dev/null

# --- Setup: Create a test tenant ---
echo "--- Setup: Create a test tenant ---"
SLUG="test-detail-${TS}"
create_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Detail Test Tenant\",\"slug\":\"$SLUG\",\"industry\":\"tech\",\"countryCode\":\"VN\",\"ownerEmail\":\"$OWNER_EMAIL\"}")
create_body=$(echo "$create_response" | head -n -1)
create_status=$(echo "$create_response" | tail -n 1)

if [ "$create_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create test tenant (HTTP $create_status)"
    exit 1
fi
TENANT_ID=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$TENANT_ID" ]; then
    echo "SETUP FAILED: Could not extract tenant ID"
    exit 1
fi
echo "Test tenant created: $TENANT_ID"
echo ""

# --- Tests ---
echo "=== Detail Endpoint ==="

run_test "401 - Detail without auth" 401 \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/detail"

run_test "404 - Detail for non-existent tenant" 404 \
    -X GET "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/detail" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

run_test_with_body "200 - Detail happy path (no subscription yet)" 200 \
    '"currentEmployeeCount"' \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/detail" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Verify usage counts are present and zero for new tenant
detail_response=$(curl -s \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/detail" \
    -H "Authorization: Bearer $ADMIN_TOKEN")

employee_count=$(echo "$detail_response" | grep -o '"currentEmployeeCount":[0-9]*' | cut -d':' -f2 || true)
site_count=$(echo "$detail_response" | grep -o '"currentSiteCount":[0-9]*' | cut -d':' -f2 || true)
monthly_checks=$(echo "$detail_response" | grep -o '"currentMonthRandomChecks":[0-9]*' | cut -d':' -f2 || true)

echo ""
echo "--- Usage stats for new tenant ---"
echo "  currentEmployeeCount: $employee_count"
echo "  currentSiteCount:     $site_count"
echo "  currentMonthRandomChecks: $monthly_checks"

if [ "$employee_count" = "0" ] && [ "$site_count" = "0" ] && [ "$monthly_checks" = "0" ]; then
    echo "PASS: Usage counts are 0 for brand-new tenant"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected zero usage for new tenant"
    FAIL=$((FAIL + 1))
fi

# Verify tenant core fields are present
if echo "$detail_response" | grep -q "\"slug\":\"$SLUG\""; then
    echo "PASS: Tenant slug present in response"
    PASS=$((PASS + 1))
else
    echo "FAIL: Tenant slug not found in response"
    FAIL=$((FAIL + 1))
fi

# Assign a subscription and verify limits appear
echo ""
echo "--- Assign subscription and check limits ---"
# Get plan list to find a plan ID
plans_response=$(curl -s \
    -X GET "$BASE_URL/api/v1/plans" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
PLAN_ID=$(echo "$plans_response" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)

if [ -n "$PLAN_ID" ]; then
    curl -s -o /dev/null \
        -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"planId\":\"$PLAN_ID\",\"billingCycle\":\"MONTHLY\"}"

    detail_with_sub=$(curl -s \
        -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/detail" \
        -H "Authorization: Bearer $ADMIN_TOKEN")

    if echo "$detail_with_sub" | grep -q '"planName"'; then
        echo "PASS: Plan name present after subscription assigned"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Plan name missing after subscription assigned"
        FAIL=$((FAIL + 1))
    fi
else
    echo "WARNING: Could not fetch plans — subscription limits test skipped"
fi

echo ""
echo "=== Results ==="
echo "PASS: $PASS"
echo "FAIL: $FAIL"
echo ""
if [ "$FAIL" -eq 0 ]; then
    echo "All tests passed."
    exit 0
else
    echo "$FAIL test(s) failed."
    exit 1
fi
