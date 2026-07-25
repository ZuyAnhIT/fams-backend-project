#!/usr/bin/env bash
# Tests for Issue #8 (docs/issues/ISSUES.md): deactivating a plan must migrate tenants still
# subscribed to it to another plan FIRST, refusing entirely if the migration would exceed the
# target plan's limits — instead of silently stranding tenants on a dead plan (previous behavior).
# Usage: BASE_URL=http://localhost:8080 bash test_plan_deactivation_migration.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0
TS=$(date +%s)

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

echo "=== Plan Deactivation Migration Tests (Issue #8) ==="
echo "Target: $BASE_URL"
echo ""

# --- Setup: Login as platform admin ---
echo "--- Setup: Login as platform admin ---"
ADMIN_TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
[ -z "$ADMIN_TOKEN" ] && echo "SETUP FAILED: no admin token" && exit 1

create_plan() {
    # $1=name $2=displayName -> echoes plan id
    curl -s -X POST "$BASE_URL/api/v1/plans" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
        -d "{\"name\":\"$1\",\"displayName\":\"$2\",\"priceMonthly\":0,\"priceYearly\":0,\"sortOrder\":999}" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])"
}

set_plan_max_employees() {
    # $1=planId $2=maxEmployees
    curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/plans/$1/limits" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
        -d "{\"maxEmployees\":$2}"
}

echo "--- Setup: Create disposable test plans ---"
SOURCE_PLAN=$(create_plan "test-src-$TS" "Test Source $TS")
TARGET_SMALL=$(create_plan "test-tgt-small-$TS" "Test Target Small $TS")
TARGET_BIG=$(create_plan "test-tgt-big-$TS" "Test Target Big $TS")
TARGET_INACTIVE=$(create_plan "test-tgt-inactive-$TS" "Test Target Inactive $TS")
EMPTY_PLAN=$(create_plan "test-empty-$TS" "Test Empty $TS")
echo "source=$SOURCE_PLAN target_small=$TARGET_SMALL target_big=$TARGET_BIG target_inactive=$TARGET_INACTIVE empty=$EMPTY_PLAN"

set_plan_max_employees "$TARGET_SMALL" 1
set_plan_max_employees "$TARGET_BIG" 100

# Deactivate target_inactive up front (no tenants on it yet, so this must succeed immediately)
inactive_deactivate_status=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH "$BASE_URL/api/v1/plans/$TARGET_INACTIVE" \
    -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" -d '{"isActive":false}')

echo ""
echo "--- Test 1: Deactivating a plan with zero subscribed tenants succeeds instantly ---"
if [ "$inactive_deactivate_status" -eq 200 ]; then
    pass "Empty-of-tenants plan deactivates without migrateToPlanId (HTTP 200)"
else
    fail "Expected 200, got $inactive_deactivate_status"
fi

echo ""
echo "--- Setup: Create a tenant, move it onto the source plan, add 3 employees ---"
SLUG="plan-migration-test-$TS"
TENANT_ID=$(curl -s -X POST "$BASE_URL/api/v1/tenants" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "{\"name\":\"Plan Migration Test $TS\",\"slug\":\"$SLUG\",\"industry\":\"tech\",\"countryCode\":\"VN\"}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
[ -z "$TENANT_ID" ] && echo "SETUP FAILED: no tenant id" && exit 1

curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "{\"planId\":\"$SOURCE_PLAN\"}"

for i in 1 2 3; do
    curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
        -d "{\"firstName\":\"Test\",\"lastName\":\"Employee $i\"}"
done
echo "Tenant $TENANT_ID now has 3 employees on plan $SOURCE_PLAN"

echo ""
echo "--- Test 2: Deactivate source WITHOUT migrateToPlanId -> 409 (tenant still on it) ---"
resp=$(curl -s -w "\n%{http_code}" -X PATCH "$BASE_URL/api/v1/plans/$SOURCE_PLAN" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d '{"isActive":false}')
status=$(echo "$resp" | tail -n1); body=$(echo "$resp" | head -n -1)
if [ "$status" -eq 409 ] && echo "$body" | grep -q "PLAN_DEACTIVATION_BLOCKED"; then
    pass "Blocked without migrateToPlanId (HTTP 409, PLAN_DEACTIVATION_BLOCKED)"
else
    fail "Expected 409/PLAN_DEACTIVATION_BLOCKED, got $status: $body"
fi

echo ""
echo "--- Test 3: migrateToPlanId == source plan itself -> 409 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH "$BASE_URL/api/v1/plans/$SOURCE_PLAN" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "{\"isActive\":false,\"migrateToPlanId\":\"$SOURCE_PLAN\"}")
if [ "$status" -eq 409 ]; then pass "Same plan as source/target rejected (HTTP 409)"; else fail "Expected 409, got $status"; fi

echo ""
echo "--- Test 4: migrateToPlanId points to an already-inactive plan -> 409 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH "$BASE_URL/api/v1/plans/$SOURCE_PLAN" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "{\"isActive\":false,\"migrateToPlanId\":\"$TARGET_INACTIVE\"}")
if [ "$status" -eq 409 ]; then pass "Inactive target plan rejected (HTTP 409)"; else fail "Expected 409, got $status"; fi

echo ""
echo "--- Test 5: migrateToPlanId whose limits the tenant would exceed (3 employees > 1) -> 409 ---"
resp=$(curl -s -w "\n%{http_code}" -X PATCH "$BASE_URL/api/v1/plans/$SOURCE_PLAN" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "{\"isActive\":false,\"migrateToPlanId\":\"$TARGET_SMALL\"}")
status=$(echo "$resp" | tail -n1); body=$(echo "$resp" | head -n -1)
if [ "$status" -eq 409 ] && echo "$body" | grep -q "vượt hạn mức"; then
    pass "Limit-exceeding migration blocked (HTTP 409, mentions vượt hạn mức)"
else
    fail "Expected 409 mentioning the limit violation, got $status: $body"
fi
# Confirm nothing changed: source plan must still be active, tenant still on source plan
still_active=$(curl -s "$BASE_URL/api/v1/plans/$SOURCE_PLAN" -H "Authorization: Bearer $ADMIN_TOKEN" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['isActive'])")
sub_plan=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" -H "Authorization: Bearer $ADMIN_TOKEN" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['planId'])")
if [ "$still_active" = "True" ] && [ "$sub_plan" = "$SOURCE_PLAN" ]; then
    pass "Blocked migration is atomic — source plan still active, tenant still on source plan"
else
    fail "Expected no side effects after blocked migration, got isActive=$still_active planId=$sub_plan"
fi

echo ""
echo "--- Test 6: migrateToPlanId with sufficient limits -> success, tenant actually migrated ---"
resp=$(curl -s -w "\n%{http_code}" -X PATCH "$BASE_URL/api/v1/plans/$SOURCE_PLAN" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "{\"isActive\":false,\"migrateToPlanId\":\"$TARGET_BIG\"}")
status=$(echo "$resp" | tail -n1)
if [ "$status" -eq 200 ]; then pass "Migration with sufficient limits succeeds (HTTP 200)"; else fail "Expected 200, got $status"; fi

sub_plan_after=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" -H "Authorization: Bearer $ADMIN_TOKEN" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['planId'])")
source_active_after=$(curl -s "$BASE_URL/api/v1/plans/$SOURCE_PLAN" -H "Authorization: Bearer $ADMIN_TOKEN" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['isActive'])")
if [ "$sub_plan_after" = "$TARGET_BIG" ] && [ "$source_active_after" = "False" ]; then
    pass "Tenant actually migrated to target plan, and source plan is now inactive"
else
    fail "Expected tenant on $TARGET_BIG and source inactive, got planId=$sub_plan_after sourceActive=$source_active_after"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
