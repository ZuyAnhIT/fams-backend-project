#!/usr/bin/env bash
# Tests for /api/v1/plans (create, list, get, update)
# Usage: BASE_URL=http://localhost:8080 bash test_plans.sh

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

echo "=== Plan Management Tests ==="
echo "Target: $BASE_URL"
echo ""

# Login as platform admin
echo "--- Setup: Login as platform admin ---"
login_body=$(curl -s \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$ADMIN_TOKEN" ] && echo "SETUP FAILED: no admin token" && exit 1
echo "Admin token obtained."

# Register a regular user
TS=$(date +%s)
reg_body=$(curl -s \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"regular_plan_$TS@fams.com\",\"password\":\"Regular@1234\",\"displayName\":\"Regular\"}")
REGULAR_TOKEN=$(echo "$reg_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Regular user token obtained."
echo ""

# Test 1: List plans (authenticated), should include seeded plans
echo "--- Test 1: List all plans (authenticated) ---"
list_response=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/plans" \
    -H "Authorization: Bearer $REGULAR_TOKEN")
list_body=$(echo "$list_response" | head -n -1)
list_status=$(echo "$list_response" | tail -n 1)

if [ "$list_status" -eq 200 ]; then
    count=$(echo "$list_body" | grep -o '"name"' | wc -l | tr -d ' ')
    if [ "$count" -ge 4 ]; then
        echo "PASS: List plans (HTTP 200, $count plans including seeded)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: List plans — expected ≥4 seeded plans, got $count"
        echo "Body: $list_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: List plans — expected HTTP 200, got HTTP $list_status"
    FAIL=$((FAIL + 1))
fi

# Test 2: List active only
echo ""
echo "--- Test 2: List active plans only ---"
active_response=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/plans?activeOnly=true" \
    -H "Authorization: Bearer $REGULAR_TOKEN")
active_status=$(echo "$active_response" | tail -n 1)
active_body=$(echo "$active_response" | head -n -1)
if [ "$active_status" -eq 200 ]; then
    active_count=$(echo "$active_body" | grep -o '"name"' | wc -l | tr -d ' ')
    echo "PASS: List active plans (HTTP 200, $active_count active)"
    PASS=$((PASS + 1))
else
    echo "FAIL: List active plans — expected HTTP 200, got HTTP $active_status"
    FAIL=$((FAIL + 1))
fi

# Extract first plan id for get test
BASIC_PLAN_ID=$(echo "$list_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)

# Test 3: Get plan by id
echo ""
echo "--- Test 3: Get plan by id ---"
if [ -n "$BASIC_PLAN_ID" ]; then
    get_response=$(curl -s -w "\n%{http_code}" \
        -X GET "$BASE_URL/api/v1/plans/$BASIC_PLAN_ID" \
        -H "Authorization: Bearer $REGULAR_TOKEN")
    get_body=$(echo "$get_response" | head -n -1)
    get_status=$(echo "$get_response" | tail -n 1)
    if [ "$get_status" -eq 200 ]; then
        plan_id=$(echo "$get_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
        if [ "$plan_id" = "$BASIC_PLAN_ID" ]; then
            echo "PASS: Get plan by id (HTTP 200)"
            PASS=$((PASS + 1))
        else
            echo "FAIL: Get plan — id mismatch"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Get plan — expected HTTP 200, got HTTP $get_status"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Get plan by id (no plan id from list)"
fi

# Test 4: Get non-existent plan → 404
echo ""
echo "--- Test 4: Get non-existent plan ---"
run_test "Plan not found" 404 \
    -X GET "$BASE_URL/api/v1/plans/00000000-0000-0000-0000-000000000000" \
    -H "Authorization: Bearer $REGULAR_TOKEN"

# Test 5: Create plan — happy path (Platform Admin)
echo ""
echo "--- Test 5: Create plan (Platform Admin) ---"
create_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/plans" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"custom-$TS\",\"displayName\":\"Custom Plan\",\"description\":\"A custom plan\",\"priceMonthly\":49.99,\"priceYearly\":499.99,\"sortOrder\":5}")
create_body=$(echo "$create_response" | head -n -1)
create_status=$(echo "$create_response" | tail -n 1)

if [ "$create_status" -eq 201 ]; then
    NEW_PLAN_ID=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    plan_name=$(echo "$create_body" | grep -o '"name":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    if [ -n "$NEW_PLAN_ID" ] && [ "$plan_name" = "custom-$TS" ]; then
        echo "PASS: Create plan (HTTP 201, id=$NEW_PLAN_ID name=$plan_name)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Create plan — missing id or wrong name: $plan_name"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Create plan — expected HTTP 201, got HTTP $create_status"
    echo "Body: $create_body"
    FAIL=$((FAIL + 1))
    NEW_PLAN_ID=""
fi

# Test 6: Create plan — duplicate name → 409
echo ""
echo "--- Test 6: Duplicate plan name ---"
run_test "Duplicate plan name" 409 \
    -X POST "$BASE_URL/api/v1/plans" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"custom-$TS\",\"displayName\":\"Another\",\"priceMonthly\":0,\"priceYearly\":0}"

# Test 7: Create plan — validation errors
echo ""
echo "--- Test 7: Validation errors ---"
run_test "Missing name" 400 \
    -X POST "$BASE_URL/api/v1/plans" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"displayName":"No Name Plan","priceMonthly":0,"priceYearly":0}'

run_test "Invalid name format (uppercase)" 400 \
    -X POST "$BASE_URL/api/v1/plans" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"UPPERCASE","displayName":"Bad","priceMonthly":0,"priceYearly":0}'

run_test "Negative price" 400 \
    -X POST "$BASE_URL/api/v1/plans" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"bad-price","displayName":"Bad Price","priceMonthly":-1,"priceYearly":0}'

run_test "Missing priceMonthly" 400 \
    -X POST "$BASE_URL/api/v1/plans" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"no-price","displayName":"No Price","priceYearly":0}'

# Test 8: Update plan (Platform Admin)
echo ""
echo "--- Test 8: Update plan ---"
if [ -n "$NEW_PLAN_ID" ]; then
    upd_response=$(curl -s -w "\n%{http_code}" \
        -X PATCH "$BASE_URL/api/v1/plans/$NEW_PLAN_ID" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d '{"displayName":"Custom Plan Updated","priceMonthly":59.99,"isActive":false}')
    upd_body=$(echo "$upd_response" | head -n -1)
    upd_status=$(echo "$upd_response" | tail -n 1)

    if [ "$upd_status" -eq 200 ]; then
        disp=$(echo "$upd_body" | grep -o '"displayName":"[^"]*"' | cut -d'"' -f4 || true)
        active=$(echo "$upd_body" | grep -o '"isActive":[a-z]*' | cut -d: -f2 || true)
        if [ "$disp" = "Custom Plan Updated" ] && [ "$active" = "false" ]; then
            echo "PASS: Update plan (HTTP 200, displayName=$disp isActive=$active)"
            PASS=$((PASS + 1))
        else
            echo "FAIL: Update plan — displayName=$disp isActive=$active"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Update plan — expected HTTP 200, got HTTP $upd_status"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Update plan (no plan id)"
fi

# Test 9: Update non-existent plan → 404
echo ""
echo "--- Test 9: Update non-existent plan ---"
run_test "Update not found" 404 \
    -X PATCH "$BASE_URL/api/v1/plans/00000000-0000-0000-0000-000000000000" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"displayName":"Ghost"}'

# Test 10: Unauthorized — regular user cannot create/update
echo ""
echo "--- Test 10: Forbidden (regular user) ---"
run_test "Regular user cannot create" 403 \
    -X POST "$BASE_URL/api/v1/plans" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $REGULAR_TOKEN" \
    -d '{"name":"hacked","displayName":"Hacked","priceMonthly":0,"priceYearly":0}'

if [ -n "$NEW_PLAN_ID" ]; then
    run_test "Regular user cannot update" 403 \
        -X PATCH "$BASE_URL/api/v1/plans/$NEW_PLAN_ID" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $REGULAR_TOKEN" \
        -d '{"displayName":"Hacked"}'
fi

# Test 11: Unauthenticated — all endpoints require auth
echo ""
echo "--- Test 11: Unauthenticated ---"
run_test "Unauthenticated list" 401 \
    -X GET "$BASE_URL/api/v1/plans"

run_test "Unauthenticated get" 401 \
    -X GET "$BASE_URL/api/v1/plans/00000000-0000-0000-0000-000000000000"

run_test "Unauthenticated create" 401 \
    -X POST "$BASE_URL/api/v1/plans" \
    -H "Content-Type: application/json" \
    -d '{"name":"ghost","displayName":"Ghost","priceMonthly":0,"priceYearly":0}'

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
if [ "$FAIL" -gt 0 ]; then exit 1; fi
