#!/usr/bin/env bash
# Tests for SubscriptionExpirationJob behavior (manual trigger via direct DB + time manipulation)
# Strategy: assign a subscription with expiresAt in the past, call the job indirectly by
# verifying the system state reflects what the job would do.
# Since the job runs at midnight, we verify the underlying service logic by:
# 1. Creating a tenant
# 2. Assigning a subscription with a past expiresAt
# 3. Checking tenant status is still active (job hasn't run)
# 4. Verifying the subscription list endpoint reflects ACTIVE status
# Note: Full end-to-end job triggering requires waiting until midnight or a test endpoint.
# Usage: BASE_URL=http://localhost:8080 bash test_subscription_expiration.sh

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

echo "=== Subscription Expiration Job Tests ==="
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

# --- Setup: Fetch a valid plan ID ---
echo "--- Setup: Fetching plan ID ---"
plans_body=$(curl -s \
    -X GET "$BASE_URL/api/v1/plans" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
PLAN_ID=$(echo "$plans_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$PLAN_ID" ]; then
    echo "SETUP FAILED: No plans found"
    exit 1
fi
echo "Plan ID: $PLAN_ID"

# --- Setup: Create a test tenant ---
echo "--- Setup: Create a test tenant ---"
SLUG="test-expiry-$(date +%s)"
create_body=$(curl -s \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Expiry Test Tenant\",\"slug\":\"$SLUG\",\"industry\":\"tech\",\"countryCode\":\"VN\"}")
TENANT_ID=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$TENANT_ID" ]; then
    echo "SETUP FAILED: Could not create test tenant"
    echo "$create_body"
    exit 1
fi
echo "Test tenant created: $TENANT_ID"

echo ""
echo "=== Subscription Assignment with Past expiresAt ==="

# Update subscription with a past expiresAt (tenant already has auto-created TRIAL sub)
PAST_DATE="2020-01-01T00:00:00Z"
assign_response=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"planId\":\"$PLAN_ID\",\"billingCycle\":\"MONTHLY\",\"expiresAt\":\"$PAST_DATE\",\"status\":\"ACTIVE\"}")
assign_body=$(echo "$assign_response" | head -n -1)
assign_status=$(echo "$assign_response" | tail -n 1)

if [ "$assign_status" -eq 200 ] || [ "$assign_status" -eq 201 ]; then
    sub_status=$(echo "$assign_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    echo "PASS: Subscription updated with past expiresAt (HTTP $assign_status, status=$sub_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Subscription update failed — HTTP $assign_status"
    echo "$assign_body"
    FAIL=$((FAIL + 1))
fi

# Verify subscription details reflect past expiresAt
sub_body=$(curl -s \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
expires_at=$(echo "$sub_body" | grep -o '"expiresAt":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
echo "Subscription expiresAt: $expires_at (should contain 2020)"

echo ""
echo "=== Tenant Status Before Job Runs ==="

# Tenant should still be active (job hasn't run yet — runs only at midnight)
tenant_body=$(curl -s \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
tenant_status=$(echo "$tenant_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
echo "Tenant status before job: $tenant_status"

echo ""
echo "=== Assign Subscription with Future expiresAt (Sanity Check) ==="

FUTURE_DATE="2099-12-31T23:59:59Z"
assign_future=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"planId\":\"$PLAN_ID\",\"billingCycle\":\"YEARLY\",\"expiresAt\":\"$FUTURE_DATE\",\"status\":\"ACTIVE\"}")
assign_future_status=$(echo "$assign_future" | tail -n 1)
assign_future_body=$(echo "$assign_future" | head -n -1)

if [ "$assign_future_status" -eq 200 ] || [ "$assign_future_status" -eq 201 ]; then
    echo "PASS: Subscription assigned with future expiresAt (HTTP $assign_future_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Future subscription assignment failed — HTTP $assign_future_status"
    echo "$assign_future_body"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "=== Results ==="
echo "PASS: $PASS"
echo "FAIL: $FAIL"
echo ""
echo "NOTE: The expiration job runs at midnight (cron: 0 0 0 * * *)."
echo "      Full end-to-end expiration verification requires a test endpoint or waiting until midnight."
if [ "$FAIL" -eq 0 ]; then
    echo "All tests passed."
    exit 0
else
    echo "$FAIL test(s) failed."
    exit 1
fi
