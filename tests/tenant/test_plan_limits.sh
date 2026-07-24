#!/usr/bin/env bash
# Tests for plan limit enforcement (task 135)
# Verifies that employee and site creation is blocked when plan limits are reached.
# Uses the 'trial' plan which limits: max_employees=5, max_sites=1
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

echo "=== Plan Limit Enforcement Tests ==="
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
    echo "SETUP FAILED: Cannot login (HTTP $login_status)"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."

# --- Setup: Register an existing user to be the tenant's owner ---
# Platform-admin-provisioned tenants now require an existing FAMS user as owner (direct
# assignment, not an invitation) — see TenantService.createTenant.
TS=$(date +%s)
OWNER_EMAIL="plan_limits_owner_${TS}@fams.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/register" -H "Content-Type: application/json" \
    -d "{\"email\":\"$OWNER_EMAIL\",\"password\":\"TestPass1\",\"displayName\":\"Plan Limits Owner\"}"
docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
    "UPDATE users SET email_verified = true WHERE email = '$OWNER_EMAIL';" > /dev/null

# --- Setup: Create a test tenant ---
echo "--- Setup: Create a test tenant ---"
SLUG="limits-test-${TS}"
create_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Limits Test Tenant\",\"slug\":\"$SLUG\",\"industry\":\"tech\",\"countryCode\":\"VN\",\"ownerEmail\":\"$OWNER_EMAIL\"}")
create_body=$(echo "$create_resp" | head -n -1)
create_status=$(echo "$create_resp" | tail -n 1)
if [ "$create_status" -ne 201 ]; then
    echo "SETUP FAILED: Cannot create tenant (HTTP $create_status)"
    exit 1
fi
TENANT_ID=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant created: $TENANT_ID"

# --- Setup: Assign 'trial' plan (max_employees=5, max_sites=1) ---
# TenantService.createTenant now auto-assigns the tenant's subscription at creation time
# (the default lowest-cost/trial plan, since no planId was given in the Setup step above) —
# no separate "assign subscription" call is needed or possible anymore (POST .../subscription
# would 409, since one already exists). Confirm it landed on the plan this test needs.
echo "--- Setup: Verify auto-assigned subscription is the trial plan ---"
sub_resp=$(curl -s -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" -H "Authorization: Bearer $ADMIN_TOKEN")
sub_plan_name=$(echo "$sub_resp" | grep -o '"planName":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
if [ "$sub_plan_name" != "trial" ]; then
    echo "SETUP FAILED: Expected auto-assigned plan 'trial', got '$sub_plan_name'"
    exit 1
fi
echo "Confirmed: tenant is on the trial plan (max_employees=5, max_sites=1)."
echo ""

# ============================================================
# EMPLOYEE LIMIT TESTS (trial: max_employees = 5)
# ============================================================
echo "=== Employee Limit (trial: max=5) ==="

create_employee() {
    local n="$1"
    curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"firstName\":\"Test\",\"lastName\":\"Employee$n\",\"employeeCode\":\"EMP-$SLUG-$n\"}"
}

echo "Creating 5 employees (all should succeed)..."
for i in 1 2 3 4 5; do
    s=$(create_employee "$i")
    if [ "$s" -eq 201 ]; then
        echo "  Employee $i: PASS (HTTP 201)"
        PASS=$((PASS + 1))
    else
        echo "  Employee $i: FAIL (HTTP $s, expected 201)"
        FAIL=$((FAIL + 1))
    fi
done

echo ""
echo "Creating 6th employee (should be blocked — limit=5)..."
s=$(create_employee "6")
if [ "$s" -eq 422 ]; then
    echo "PASS: 6th employee blocked (HTTP 422)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 422, got HTTP $s"
    FAIL=$((FAIL + 1))
fi

echo ""

# ============================================================
# SITE LIMIT TESTS (trial: max_sites = 1)
# ============================================================
echo "=== Site Limit (trial: max=1) ==="

create_site() {
    local n="$1"
    curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"name\":\"Test Site $n $SLUG\",\"latitude\":10.0,\"longitude\":106.0}"
}

echo "Creating 1st site (should succeed)..."
s=$(create_site "1")
if [ "$s" -eq 201 ]; then
    echo "PASS: 1st site created (HTTP 201)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 201, got HTTP $s"
    FAIL=$((FAIL + 1))
fi

echo "Creating 2nd site (should be blocked — limit=1)..."
s=$(create_site "2")
if [ "$s" -eq 422 ]; then
    echo "PASS: 2nd site blocked (HTTP 422)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 422, got HTTP $s"
    FAIL=$((FAIL + 1))
fi

echo ""

# ============================================================
# NO-SUBSCRIPTION TENANT: no limits enforced
# ============================================================
echo "=== No-Subscription Tenant: limits not enforced ==="

SLUG2="limits-nosub-$(date +%s)"
nosub_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"NoSub Tenant\",\"slug\":\"$SLUG2\",\"industry\":\"tech\",\"countryCode\":\"VN\"}")
NOSUB_TENANT_ID=$(echo "$nosub_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)

if [ -n "$NOSUB_TENANT_ID" ]; then
    s=$(create_employee "nosub-1")
    # Re-create for this tenant
    s2=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$BASE_URL/api/v1/tenants/$NOSUB_TENANT_ID/employees" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"firstName\":\"No\",\"lastName\":\"Sub\",\"employeeCode\":\"EMP-NOSUB-1\"}")
    if [ "$s2" -eq 201 ]; then
        echo "PASS: Employee created on tenant with no subscription (no limit)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected 201 for no-subscription tenant, got HTTP $s2"
        FAIL=$((FAIL + 1))
    fi
else
    echo "WARNING: Could not create no-subscription tenant — skipping test"
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
