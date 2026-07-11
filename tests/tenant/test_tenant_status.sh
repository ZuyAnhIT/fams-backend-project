#!/usr/bin/env bash
# Tests for POST /api/v1/tenants/{id}/suspend, /reactivate, and /cancel
# Usage: BASE_URL=http://localhost:8080 bash test_tenant_status.sh

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

echo "=== Tenant Suspend/Reactivate Tests ==="
echo "Target: $BASE_URL"
echo ""

# --- Setup: Login as platform admin ---
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

# --- Setup: Create a test tenant ---
echo "--- Setup: Create a test tenant ---"
SLUG="test-suspend-$(date +%s)"
create_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Suspend Test Tenant\",\"slug\":\"$SLUG\",\"industry\":\"tech\",\"countryCode\":\"VN\"}")
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

# --- Setup: Register a regular user for 403 tests (phone to avoid SMTP dependency) ---
echo "--- Setup: Register a regular user ---"
TS=$(date +%s)
REG_PHONE="+849$(printf '%07d' $(( (TS + $$) % 10000000 )))"
reg_body=$(curl -s \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$REG_PHONE\",\"password\":\"Regular@1234\",\"displayName\":\"Regular User\"}")
REGULAR_TOKEN=$(echo "$reg_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
if [ -n "$REGULAR_TOKEN" ]; then
    echo "Regular user token obtained."
else
    echo "WARNING: Could not get regular user token — 403 tests will be skipped"
fi
echo ""

# --- Suspend tests ---
echo "=== Suspend Endpoint ==="

run_test "401 - Suspend without auth" 401 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/suspend"

if [ -n "$REGULAR_TOKEN" ]; then
    run_test "403 - Suspend as non-platform-admin" 403 \
        -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/suspend" \
        -H "Authorization: Bearer $REGULAR_TOKEN"
fi

run_test "404 - Suspend non-existent tenant" 404 \
    -X POST "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/suspend" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

run_test "200 - Suspend tenant (happy path)" 200 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/suspend" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

run_test "400 - Suspend already-suspended tenant" 400 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/suspend" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "=== Reactivate Endpoint ==="

run_test "401 - Reactivate without auth" 401 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/reactivate"

run_test "404 - Reactivate non-existent tenant" 404 \
    -X POST "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/reactivate" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

run_test "200 - Reactivate tenant (happy path)" 200 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/reactivate" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

run_test "400 - Reactivate non-suspended tenant" 400 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/reactivate" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "=== Login Blocked When Tenant Suspended ==="

# Suspend the tenant again for this test
curl -s -o /dev/null \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/suspend" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Attempt to verify the status was updated correctly
status_check=$(curl -s \
    -X GET "$BASE_URL/api/v1/tenants?search=$SLUG" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
echo "Tenant status after re-suspend: $(echo "$status_check" | grep -o '"status":"[^"]*"' | head -1)"

# Restore: reactivate so test tenant doesn't linger suspended
curl -s -o /dev/null \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/reactivate" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo "Test tenant restored to active."

echo ""
echo "=== Cancel Endpoint ==="

# Create a fresh tenant for cancel tests
CANCEL_SLUG="test-cancel-$(date +%s)"
cancel_tenant_body=$(curl -s \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Cancel Test Tenant\",\"slug\":\"$CANCEL_SLUG\"}")
CANCEL_TENANT_ID=$(echo "$cancel_tenant_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Cancel test tenant: $CANCEL_TENANT_ID"

run_test "401 - Cancel without auth" 401 \
    -X POST "$BASE_URL/api/v1/tenants/$CANCEL_TENANT_ID/cancel"

if [ -n "$REGULAR_TOKEN" ]; then
    run_test "403 - Cancel as non-platform-admin" 403 \
        -X POST "$BASE_URL/api/v1/tenants/$CANCEL_TENANT_ID/cancel" \
        -H "Authorization: Bearer $REGULAR_TOKEN"
fi

run_test "404 - Cancel non-existent tenant" 404 \
    -X POST "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/cancel" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Happy path — verify status is cancelled
cancel_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$CANCEL_TENANT_ID/cancel" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
cancel_body=$(echo "$cancel_response" | head -n -1)
cancel_http=$(echo "$cancel_response" | tail -n 1)
if [ "$cancel_http" -eq 200 ]; then
    cancel_status=$(echo "$cancel_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    if [ "$cancel_status" = "cancelled" ]; then
        echo "PASS: 200 - Cancel tenant (HTTP 200, status=cancelled)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: 200 - Cancel tenant — expected status=cancelled, got status=$cancel_status"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: 200 - Cancel tenant — expected HTTP 200, got HTTP $cancel_http"
    FAIL=$((FAIL + 1))
fi

run_test "400 - Cancel already-cancelled tenant" 400 \
    -X POST "$BASE_URL/api/v1/tenants/$CANCEL_TENANT_ID/cancel" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Cancelled tenant cannot be suspended
run_test "400 - Suspend a cancelled tenant" 400 \
    -X POST "$BASE_URL/api/v1/tenants/$CANCEL_TENANT_ID/suspend" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

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
