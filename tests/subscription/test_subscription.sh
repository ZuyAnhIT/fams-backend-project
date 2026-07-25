#!/usr/bin/env bash
# Tests for POST/GET/PATCH /api/v1/tenants/{id}/subscription
# Usage: BASE_URL=http://localhost:8080 bash test_subscription.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

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

echo "=== Subscription Tests ==="
echo "Target: $BASE_URL"
echo ""

# Setup
echo "--- Setup: Login ---"
login_body=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$ADMIN_TOKEN" ] && echo "SETUP FAILED: no admin token" && exit 1
echo "Admin token obtained."

TS=$(date +%s)
REGULAR_TOKEN=$(register_verified_test_user_token "$BASE_URL" "R")
echo "Regular user token obtained."

# Create a tenant owned by admin
tenant_body=$(curl -s -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Sub Test Corp $TS\",\"slug\":\"sub-test-$TS\"}")
TENANT_ID=$(echo "$tenant_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
[ -z "$TENANT_ID" ] && echo "SETUP FAILED: no tenant id" && exit 1
echo "Tenant id: $TENANT_ID"

# Get trial plan id
plans_body=$(curl -s -X GET "$BASE_URL/api/v1/plans" -H "Authorization: Bearer $ADMIN_TOKEN")
TRIAL_PLAN_ID=$(echo "$plans_body" | grep -o '"id":"[^"]*","name":"trial"' | grep -o '"id":"[^"]*"' | cut -d'"' -f4 || true)
if [ -z "$TRIAL_PLAN_ID" ]; then
    TRIAL_PLAN_ID=$(echo "$plans_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
fi
[ -z "$TRIAL_PLAN_ID" ] && echo "SETUP FAILED: no plan id" && exit 1

BASIC_PLAN_ID=$(echo "$plans_body" | grep -o '"id":"[^"]*","name":"basic"' | grep -o '"id":"[^"]*"' | cut -d'"' -f4 || true)
[ -z "$BASIC_PLAN_ID" ] && BASIC_PLAN_ID="$TRIAL_PLAN_ID"

echo "Trial plan id: $TRIAL_PLAN_ID"
echo "Basic plan id: $BASIC_PLAN_ID"
echo ""

# Test 1: GET subscription — auto-created as TRIAL on tenant creation
echo "--- Test 1: GET subscription auto-created on tenant creation → 200 ---"
auto_sub_response=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
auto_sub_body=$(echo "$auto_sub_response" | head -n -1)
auto_sub_status=$(echo "$auto_sub_response" | tail -n 1)

if [ "$auto_sub_status" -eq 200 ]; then
    auto_tenant_id=$(echo "$auto_sub_body" | grep -o '"tenantId":"[^"]*"' | cut -d'"' -f4 || true)
    auto_status=$(echo "$auto_sub_body" | grep -o '"status":"[^"]*"' | cut -d'"' -f4 || true)
    if [ "$auto_tenant_id" = "$TENANT_ID" ] && [ "$auto_status" = "TRIAL" ]; then
        echo "PASS: Auto-created subscription (HTTP 200, tenantId=$auto_tenant_id status=$auto_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Auto-created subscription — tenantId=$auto_tenant_id status=$auto_status"
        echo "Body: $auto_sub_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Auto-created subscription — expected HTTP 200, got HTTP $auto_sub_status"
    FAIL=$((FAIL + 1))
fi

# Test 2: POST assign → 409 (subscription already auto-created)
echo ""
echo "--- Test 2: POST assign → 409 (auto-created subscription already exists) ---"
run_test "Assign blocked — already exists → 409" 409 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"planId\":\"$TRIAL_PLAN_ID\",\"billingCycle\":\"MONTHLY\",\"status\":\"TRIAL\"}"

# Test 3: POST again → 409 conflict (idempotency check)
echo ""
echo "--- Test 3: Assign subscription again → 409 ---"
run_test "Duplicate assign → 409" 409 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"planId\":\"$TRIAL_PLAN_ID\",\"billingCycle\":\"MONTHLY\"}"

# Test 4: GET subscription (admin)
echo ""
echo "--- Test 4: GET subscription (admin) ---"
get_response=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
get_body=$(echo "$get_response" | head -n -1)
get_status=$(echo "$get_response" | tail -n 1)

if [ "$get_status" -eq 200 ]; then
    tenant_id_in_resp=$(echo "$get_body" | grep -o '"tenantId":"[^"]*"' | cut -d'"' -f4 || true)
    if [ "$tenant_id_in_resp" = "$TENANT_ID" ]; then
        echo "PASS: GET subscription (HTTP 200, tenantId=$tenant_id_in_resp)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: GET subscription — tenantId=$tenant_id_in_resp"
        echo "Body: $get_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: GET subscription — expected HTTP 200, got HTTP $get_status"
    FAIL=$((FAIL + 1))
fi

# Test 5: PATCH subscription — change plan and billing cycle
echo ""
echo "--- Test 5: PATCH subscription (change plan + billingCycle) ---"
patch_response=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"planId\":\"$BASIC_PLAN_ID\",\"billingCycle\":\"YEARLY\",\"status\":\"ACTIVE\"}")
patch_body=$(echo "$patch_response" | head -n -1)
patch_status=$(echo "$patch_response" | tail -n 1)

if [ "$patch_status" -eq 200 ]; then
    new_billing=$(echo "$patch_body" | grep -o '"billingCycle":"[^"]*"' | cut -d'"' -f4 || true)
    new_status=$(echo "$patch_body" | grep -o '"status":"[^"]*"' | cut -d'"' -f4 || true)
    if [ "$new_billing" = "YEARLY" ] && [ "$new_status" = "ACTIVE" ]; then
        echo "PASS: PATCH subscription (HTTP 200, billingCycle=$new_billing status=$new_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: PATCH subscription — billingCycle=$new_billing status=$new_status"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: PATCH subscription — expected HTTP 200, got HTTP $patch_status"
    echo "Body: $patch_body"
    FAIL=$((FAIL + 1))
fi

# Test 6: PATCH — cancel subscription
echo ""
echo "--- Test 6: PATCH — cancel subscription ---"
cancel_response=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"CANCELLED"}')
cancel_body=$(echo "$cancel_response" | head -n -1)
cancel_status=$(echo "$cancel_response" | tail -n 1)

if [ "$cancel_status" -eq 200 ]; then
    cancelled_status=$(echo "$cancel_body" | grep -o '"status":"[^"]*"' | cut -d'"' -f4 || true)
    has_cancelled_at=$(echo "$cancel_body" | grep -o '"cancelledAt":"[^"]*"' || true)
    if [ "$cancelled_status" = "CANCELLED" ] && [ -n "$has_cancelled_at" ]; then
        echo "PASS: Cancel subscription (HTTP 200, status=CANCELLED, cancelledAt set)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Cancel subscription — status=$cancelled_status cancelledAt check failed"
        echo "Body: $cancel_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Cancel subscription — expected HTTP 200, got HTTP $cancel_status"
    FAIL=$((FAIL + 1))
fi

# Test 7: PATCH with expiresAt + clearExpiresAt
echo ""
echo "--- Test 7: PATCH set expiresAt then clear ---"
run_test "Set expiresAt" 200 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"expiresAt":"2026-12-31T23:59:59Z","status":"ACTIVE"}'

clear_exp_response=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"clearExpiresAt":true}')
clear_exp_body=$(echo "$clear_exp_response" | head -n -1)
clear_exp_status=$(echo "$clear_exp_response" | tail -n 1)

if [ "$clear_exp_status" -eq 200 ]; then
    has_null_exp=$(echo "$clear_exp_body" | grep -o '"expiresAt":null' || true)
    if [ -n "$has_null_exp" ]; then
        echo "PASS: clearExpiresAt (HTTP 200, expiresAt=null)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: clearExpiresAt — expected null but got: $(echo "$clear_exp_body" | grep -o '"expiresAt":[^,}]*' || true)"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: clearExpiresAt — expected HTTP 200, got HTTP $clear_exp_status"
    FAIL=$((FAIL + 1))
fi

# Test 8: Tenant not found → 404
echo ""
echo "--- Test 8: Tenant not found ---"
FAKE_ID="00000000-0000-0000-0000-000000000000"
run_test "POST assign tenant not found" 404 \
    -X POST "$BASE_URL/api/v1/tenants/$FAKE_ID/subscription" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"planId\":\"$TRIAL_PLAN_ID\",\"billingCycle\":\"MONTHLY\"}"

run_test "GET subscription tenant not found" 404 \
    -X GET "$BASE_URL/api/v1/tenants/$FAKE_ID/subscription" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

run_test "PATCH subscription tenant not found" 404 \
    -X PATCH "$BASE_URL/api/v1/tenants/$FAKE_ID/subscription" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"ACTIVE"}'

# Test 9: Unauthenticated → 401
echo ""
echo "--- Test 9: Unauthenticated ---"
run_test "Unauthenticated GET" 401 \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription"

run_test "Unauthenticated POST" 401 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Content-Type: application/json" \
    -d "{\"planId\":\"$TRIAL_PLAN_ID\",\"billingCycle\":\"MONTHLY\"}"

run_test "Unauthenticated PATCH" 401 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Content-Type: application/json" \
    -d '{"status":"ACTIVE"}'

# Test 10: Regular user cannot POST or PATCH → 403
echo ""
echo "--- Test 10: Regular user forbidden for POST/PATCH ---"
run_test "Regular user POST → 403" 403 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $REGULAR_TOKEN" \
    -d "{\"planId\":\"$TRIAL_PLAN_ID\",\"billingCycle\":\"MONTHLY\"}"

run_test "Regular user PATCH → 403" 403 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $REGULAR_TOKEN" \
    -d '{"status":"ACTIVE"}'

# Test 11: Regular user cannot GET other tenant's subscription → 403
echo ""
echo "--- Test 11: Regular user GET other tenant subscription → 403 ---"
run_test "Regular user GET other tenant subscription → 403" 403 \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Authorization: Bearer $REGULAR_TOKEN"

# Test 12: Required fields validation
echo ""
echo "--- Test 12: Validation — missing required fields ---"
run_test "POST missing planId → 400" 400 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"billingCycle":"MONTHLY"}'

run_test "POST missing billingCycle → 400" 400 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"planId\":\"$TRIAL_PLAN_ID\"}"

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
if [ "$FAIL" -gt 0 ]; then exit 1; fi
