#!/usr/bin/env bash
# Tests for POST /api/v1/tenants/{tenantId}/invitations (send employee invitation)
# Usage: BASE_URL=http://localhost:8080 bash test_invite_employee.sh

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

echo "=== Employee Invitation Tests ==="
echo "Target: $BASE_URL"
echo ""

# Setup: login as platform admin
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

# Setup: create a tenant to test against
echo "--- Setup: Create test tenant ---"
UNIQUE_SLUG="invite-test-$(date +%s)"
tenant_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Invite Test Corp\",\"slug\":\"$UNIQUE_SLUG\"}")

tenant_body=$(echo "$tenant_response" | head -n -1)
tenant_status=$(echo "$tenant_response" | tail -n 1)

if [ "$tenant_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $tenant_status)"
    exit 1
fi

TENANT_ID=$(echo "$tenant_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$TENANT_ID" ]; then
    echo "SETUP FAILED: Could not extract tenant id"
    exit 1
fi
echo "Tenant created: id=$TENANT_ID"
echo ""

# Setup: register a regular user without tenant permissions for 403 tests
echo "--- Setup: Register regular user ---"
REG_RESPONSE=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d '{"email":"noperm_invite_test@fams.com","password":"Regular@1234","displayName":"No Perm User"}')
REG_STATUS=$(echo "$REG_RESPONSE" | tail -n 1)
REG_BODY=$(echo "$REG_RESPONSE" | head -n -1)

if [ "$REG_STATUS" -eq 201 ] || [ "$REG_STATUS" -eq 409 ]; then
    if [ "$REG_STATUS" -eq 201 ]; then
        REGULAR_TOKEN=$(echo "$REG_BODY" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    else
        login2=$(curl -s \
            -X POST "$BASE_URL/api/v1/auth/login" \
            -H "Content-Type: application/json" \
            -d '{"email":"noperm_invite_test@fams.com","password":"Regular@1234"}')
        REGULAR_TOKEN=$(echo "$login2" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    fi
    echo "Regular user token obtained."
else
    REGULAR_TOKEN=""
    echo "SETUP WARNING: Could not create regular user — skipping 403 test"
fi
echo ""

INVITE_EMAIL="employee.invite.$(date +%s)@example.com"
INVITE_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/invitations"

# Test 1: Happy path — platform admin sends invitation
echo "--- Test 1: Happy path (valid invitation) ---"
invite_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$INVITE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"John\",\"lastName\":\"Doe\"}")

invite_body=$(echo "$invite_response" | head -n -1)
invite_status=$(echo "$invite_response" | tail -n 1)

if [ "$invite_status" -eq 201 ]; then
    inv_id=$(echo "$invite_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ -n "$inv_id" ]; then
        echo "PASS: Happy path (HTTP 201, id=$inv_id)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Happy path — HTTP 201 but invitation id missing"
        echo "Body: $invite_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Happy path — expected HTTP 201, got HTTP $invite_status"
    echo "Body: $invite_body"
    FAIL=$((FAIL + 1))
fi

# Test 2: Duplicate invitation for same email → 409
echo ""
echo "--- Test 2: Duplicate pending invitation ---"
run_test "Duplicate invitation" 409 \
    -X POST "$INVITE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\"}"

# Test 3: Missing email → 400
echo ""
echo "--- Test 3: Missing email ---"
run_test "Missing email" 400 \
    -X POST "$INVITE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"No","lastName":"Email"}'

# Test 4: Invalid email format → 400
echo ""
echo "--- Test 4: Invalid email format ---"
run_test "Invalid email format" 400 \
    -X POST "$INVITE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"email":"not-an-email"}'

# Test 5: Unauthenticated → 401
echo ""
echo "--- Test 5: Unauthenticated ---"
run_test "Unauthenticated" 401 \
    -X POST "$INVITE_URL" \
    -H "Content-Type: application/json" \
    -d '{"email":"anon@example.com"}'

# Test 6: Regular user without permission → 403
echo ""
echo "--- Test 6: Forbidden (no employees:create permission) ---"
if [ -n "$REGULAR_TOKEN" ]; then
    run_test "No permission user forbidden" 403 \
        -X POST "$INVITE_URL" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $REGULAR_TOKEN" \
        -d '{"email":"forbidden@example.com"}'
else
    echo "SKIP: No regular token available"
fi

# Test 7: Non-existent tenant → 404
echo ""
echo "--- Test 7: Non-existent tenant ---"
FAKE_TENANT_ID="00000000-0000-0000-0000-000000000000"
run_test "Tenant not found" 404 \
    -X POST "$BASE_URL/api/v1/tenants/$FAKE_TENANT_ID/invitations" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"email":"nobody@example.com"}'

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
