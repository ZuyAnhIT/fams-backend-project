#!/usr/bin/env bash
# Tests for POST /api/v1/tenants (create tenant)
# Usage: BASE_URL=http://localhost:8080 bash test_create_tenant.sh

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

echo "=== Tenant Create Tests ==="
echo "Target: $BASE_URL"
echo ""

# Obtain platform admin token
echo "--- Setup: Login as platform admin ---"
login_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')

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
echo ""

# Register a regular (non-admin) user for 403 tests
echo "--- Setup: Register a regular user ---"
REG_RESPONSE=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d '{"email":"regularuser_tenant_test@fams.com","password":"Regular@1234","displayName":"Regular User"}')
REG_STATUS=$(echo "$REG_RESPONSE" | tail -n 1)
REG_BODY=$(echo "$REG_RESPONSE" | head -n -1)

if [ "$REG_STATUS" -eq 201 ] || [ "$REG_STATUS" -eq 409 ]; then
    if [ "$REG_STATUS" -eq 201 ]; then
        REGULAR_TOKEN=$(echo "$REG_BODY" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
    else
        # User already exists — login instead
        login2=$(curl -s \
            -X POST "$BASE_URL/api/v1/auth/login" \
            -H "Content-Type: application/json" \
            -d '{"email":"regularuser_tenant_test@fams.com","password":"Regular@1234"}')
        REGULAR_TOKEN=$(echo "$login2" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
    fi
    echo "Regular user token obtained."
else
    echo "SETUP WARNING: Could not register regular user (HTTP $REG_STATUS) — skipping 403 test"
    REGULAR_TOKEN=""
fi
echo ""

# Use a unique slug per run to avoid collisions between test runs
UNIQUE_SLUG="test-tenant-$(date +%s)"

# Test 1: Happy path — create tenant with valid data
echo "--- Test 1: Happy path (valid data) ---"
create_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Test Corp\",\"slug\":\"$UNIQUE_SLUG\",\"industry\":\"Tech\",\"timezone\":\"Asia/Ho_Chi_Minh\",\"locale\":\"vi\",\"currencyCode\":\"VND\"}")

create_body=$(echo "$create_response" | head -n -1)
create_status=$(echo "$create_response" | tail -n 1)

if [ "$create_status" -eq 201 ]; then
    tenant_id=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ -n "$tenant_id" ]; then
        echo "PASS: Happy path (HTTP 201, id=$tenant_id)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Happy path — HTTP 201 but tenant id missing in response"
        echo "Body: $create_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Happy path — expected HTTP 201, got HTTP $create_status"
    echo "Body: $create_body"
    FAIL=$((FAIL + 1))
fi

# Test 2: Duplicate slug → 409
echo ""
echo "--- Test 2: Duplicate slug ---"
run_test "Duplicate slug" 409 \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Another Corp\",\"slug\":\"$UNIQUE_SLUG\"}"

# Test 3: Missing name → 400
echo ""
echo "--- Test 3: Missing name ---"
run_test "Missing name" 400 \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"slug":"some-slug-no-name"}'

# Test 4: Missing slug → 400
echo ""
echo "--- Test 4: Missing slug ---"
run_test "Missing slug" 400 \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Some Corp"}'

# Test 5: Invalid slug format (uppercase) → 400
echo ""
echo "--- Test 5: Invalid slug format ---"
run_test "Invalid slug format" 400 \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Bad Slug Corp","slug":"UPPERCASE_SLUG"}'

# Test 6: Unauthenticated → 401
echo ""
echo "--- Test 6: Unauthenticated ---"
run_test "Unauthenticated" 401 \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -d '{"name":"Ghost Corp","slug":"ghost-corp"}'

# Test 7: Regular user (non-admin) → 403
echo ""
echo "--- Test 7: Forbidden (regular user) ---"
if [ -n "$REGULAR_TOKEN" ]; then
    run_test "Regular user forbidden" 403 \
        -X POST "$BASE_URL/api/v1/tenants" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $REGULAR_TOKEN" \
        -d '{"name":"Forbidden Corp","slug":"forbidden-corp-slug"}'
else
    echo "SKIP: Regular user forbidden (no regular token)"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
