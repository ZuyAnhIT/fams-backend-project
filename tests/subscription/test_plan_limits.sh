#!/usr/bin/env bash
# Tests for GET/PATCH /api/v1/plans/{id}/limits
# Usage: BASE_URL=http://localhost:8080 bash test_plan_limits.sh

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

echo "=== Plan Limits Tests ==="
echo "Target: $BASE_URL"
echo ""

# Login
echo "--- Setup: Login ---"
login_body=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$ADMIN_TOKEN" ] && echo "SETUP FAILED" && exit 1
echo "Admin token obtained."

TS=$(date +%s)
reg_body=$(curl -s -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"regular_limits_$TS@fams.com\",\"password\":\"Regular@1234\",\"displayName\":\"R\"}")
REGULAR_TOKEN=$(echo "$reg_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Regular user token obtained."

# Get trial plan id
plans_body=$(curl -s -X GET "$BASE_URL/api/v1/plans" -H "Authorization: Bearer $ADMIN_TOKEN")
TRIAL_PLAN_ID=$(echo "$plans_body" | grep -o '"id":"[^"]*","name":"trial"' | grep -o '"id":"[^"]*"' | cut -d'"' -f4 || true)
if [ -z "$TRIAL_PLAN_ID" ]; then
    # Fallback: grab first plan id
    TRIAL_PLAN_ID=$(echo "$plans_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
fi
[ -z "$TRIAL_PLAN_ID" ] && echo "SETUP FAILED: no plan id" && exit 1
echo "Trial plan id: $TRIAL_PLAN_ID"
echo ""

# Test 1: GET limits for trial plan — seeded values
echo "--- Test 1: GET limits returns seeded values (trial plan) ---"
get_response=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/plans/$TRIAL_PLAN_ID/limits" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
get_body=$(echo "$get_response" | head -n -1)
get_status=$(echo "$get_response" | tail -n 1)

if [ "$get_status" -eq 200 ]; then
    plan_id_in_resp=$(echo "$get_body" | grep -o '"planId":"[^"]*"' | cut -d'"' -f4 || true)
    max_emp=$(echo "$get_body" | grep -o '"maxEmployees":[0-9]*' | cut -d: -f2 || true)
    if [ "$plan_id_in_resp" = "$TRIAL_PLAN_ID" ] && [ -n "$max_emp" ]; then
        echo "PASS: GET limits (HTTP 200, planId=$plan_id_in_resp maxEmployees=$max_emp)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: GET limits — planId=$plan_id_in_resp maxEmployees=$max_emp"
        echo "Body: $get_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: GET limits — expected HTTP 200, got HTTP $get_status"
    echo "Body: $get_body"
    FAIL=$((FAIL + 1))
fi

# Test 2: PATCH limits — update specific fields
echo ""
echo "--- Test 2: PATCH limits (update maxEmployees and maxSites) ---"
patch_response=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$BASE_URL/api/v1/plans/$TRIAL_PLAN_ID/limits" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"maxEmployees":10,"maxSites":2}')
patch_body=$(echo "$patch_response" | head -n -1)
patch_status=$(echo "$patch_response" | tail -n 1)

if [ "$patch_status" -eq 200 ]; then
    max_emp=$(echo "$patch_body" | grep -o '"maxEmployees":[0-9]*' | cut -d: -f2 || true)
    max_sites=$(echo "$patch_body" | grep -o '"maxSites":[0-9]*' | cut -d: -f2 || true)
    if [ "$max_emp" = "10" ] && [ "$max_sites" = "2" ]; then
        echo "PASS: PATCH limits (HTTP 200, maxEmployees=$max_emp maxSites=$max_sites)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: PATCH limits — maxEmployees=$max_emp maxSites=$max_sites"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: PATCH limits — expected HTTP 200, got HTTP $patch_status"
    echo "Body: $patch_body"
    FAIL=$((FAIL + 1))
fi

# Test 3: PATCH limits — clear a limit (set to unlimited)
echo ""
echo "--- Test 3: Clear maxSites (set to unlimited) ---"
clear_response=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$BASE_URL/api/v1/plans/$TRIAL_PLAN_ID/limits" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"clearMaxSites":true}')
clear_body=$(echo "$clear_response" | head -n -1)
clear_status=$(echo "$clear_response" | tail -n 1)

if [ "$clear_status" -eq 200 ]; then
    # maxSites should be null (absent or "null" in JSON)
    has_null_sites=$(echo "$clear_body" | grep -o '"maxSites":null' || true)
    if [ -n "$has_null_sites" ]; then
        echo "PASS: Clear maxSites (HTTP 200, maxSites=null)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Clear maxSites — expected null but got: $(echo "$clear_body" | grep -o '"maxSites":[^,}]*' || true)"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Clear maxSites — expected HTTP 200, got HTTP $clear_status"
    FAIL=$((FAIL + 1))
fi

# Test 4: GET reflects latest changes
echo ""
echo "--- Test 4: GET reflects updated values ---"
get2_response=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/plans/$TRIAL_PLAN_ID/limits" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
get2_body=$(echo "$get2_response" | head -n -1)
get2_status=$(echo "$get2_response" | tail -n 1)

if [ "$get2_status" -eq 200 ]; then
    max_emp2=$(echo "$get2_body" | grep -o '"maxEmployees":[0-9]*' | cut -d: -f2 || true)
    has_null_sites2=$(echo "$get2_body" | grep -o '"maxSites":null' || true)
    if [ "$max_emp2" = "10" ] && [ -n "$has_null_sites2" ]; then
        echo "PASS: GET reflects update (maxEmployees=$max_emp2 maxSites=null)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: GET reflects update — maxEmployees=$max_emp2 maxSites check failed"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: GET reflects update — expected HTTP 200, got HTTP $get2_status"
    FAIL=$((FAIL + 1))
fi

# Test 5: Validation — invalid values
echo ""
echo "--- Test 5: Validation errors ---"
run_test "maxEmployees = 0 (invalid)" 400 \
    -X PATCH "$BASE_URL/api/v1/plans/$TRIAL_PLAN_ID/limits" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"maxEmployees":0}'

run_test "maxSites negative" 400 \
    -X PATCH "$BASE_URL/api/v1/plans/$TRIAL_PLAN_ID/limits" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"maxSites":-5}'

# Test 6: Plan not found → 404
echo ""
echo "--- Test 6: Plan not found ---"
FAKE_ID="00000000-0000-0000-0000-000000000000"
run_test "GET limits plan not found" 404 \
    -X GET "$BASE_URL/api/v1/plans/$FAKE_ID/limits" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

run_test "PATCH limits plan not found" 404 \
    -X PATCH "$BASE_URL/api/v1/plans/$FAKE_ID/limits" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"maxEmployees":10}'

# Test 7: Unauthenticated → 401
echo ""
echo "--- Test 7: Unauthenticated ---"
run_test "Unauthenticated GET" 401 \
    -X GET "$BASE_URL/api/v1/plans/$TRIAL_PLAN_ID/limits"

run_test "Unauthenticated PATCH" 401 \
    -X PATCH "$BASE_URL/api/v1/plans/$TRIAL_PLAN_ID/limits" \
    -H "Content-Type: application/json" \
    -d '{"maxEmployees":10}'

# Test 8: Regular user can GET but not PATCH
echo ""
echo "--- Test 8: Regular user access ---"
run_test "Regular user can GET limits" 200 \
    -X GET "$BASE_URL/api/v1/plans/$TRIAL_PLAN_ID/limits" \
    -H "Authorization: Bearer $REGULAR_TOKEN"

run_test "Regular user cannot PATCH limits" 403 \
    -X PATCH "$BASE_URL/api/v1/plans/$TRIAL_PLAN_ID/limits" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $REGULAR_TOKEN" \
    -d '{"maxEmployees":999}'

# Test 9: Auto-create defaults for plan without limits row
echo ""
echo "--- Test 9: Auto-create unlimited defaults for new plan ---"
new_plan_body=$(curl -s \
    -X POST "$BASE_URL/api/v1/plans" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"custom-limits-$TS\",\"displayName\":\"Custom\",\"priceMonthly\":0,\"priceYearly\":0}")
NEW_PLAN_ID=$(echo "$new_plan_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)

if [ -n "$NEW_PLAN_ID" ]; then
    auto_response=$(curl -s -w "\n%{http_code}" \
        -X GET "$BASE_URL/api/v1/plans/$NEW_PLAN_ID/limits" \
        -H "Authorization: Bearer $ADMIN_TOKEN")
    auto_body=$(echo "$auto_response" | head -n -1)
    auto_status=$(echo "$auto_response" | tail -n 1)

    if [ "$auto_status" -eq 200 ]; then
        # All fields should be null (unlimited)
        all_null=$(echo "$auto_body" | grep -o '"maxEmployees":null' || true)
        if [ -n "$all_null" ]; then
            echo "PASS: Auto-create unlimited defaults (HTTP 200, maxEmployees=null)"
            PASS=$((PASS + 1))
        else
            echo "FAIL: Auto-create defaults — expected null limits"
            echo "Body: $auto_body"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Auto-create defaults — expected HTTP 200, got HTTP $auto_status"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Auto-create defaults (could not create test plan)"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
if [ "$FAIL" -gt 0 ]; then exit 1; fi
