#!/usr/bin/env bash
# Tests for POST /api/v1/tenants (create tenant)
# Usage: BASE_URL=http://localhost:8080 bash test_create_tenant.sh

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

# Register a regular (non-admin) user — Issue #3 (docs/issues/ISSUES.md) made tenant
# creation self-serve for any authenticated user, so this user is no longer used for a
# 403 test; it's used to verify the self-serve create + auto TENANT_ADMIN membership flow.
echo "--- Setup: Register a regular user ---"
TS=$(date +%s)
REGULAR_TOKEN=$(register_verified_test_user_token "$BASE_URL" "Regular User")
if [ -n "$REGULAR_TOKEN" ]; then
    echo "Regular user token obtained."
else
    echo "SETUP WARNING: Could not register regular user — skipping self-serve tests"
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

# Test 7: Issue #3 — regular (non-admin) user can now self-serve create a tenant → 201,
# and is auto-assigned TENANT_ADMIN for it (previously this endpoint was platform-admin-only
# and creation never linked the creator into user_roles at all).
echo ""
echo "--- Test 7: Regular user self-serve create → 201 + auto TENANT_ADMIN ---"
SELF_SERVE_SLUG="self-serve-$(date +%s)"
if [ -n "$REGULAR_TOKEN" ]; then
    create_response=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL/api/v1/tenants" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $REGULAR_TOKEN" \
        -d "{\"name\":\"Self Serve Corp\",\"slug\":\"$SELF_SERVE_SLUG\"}")
    create_body=$(echo "$create_response" | head -n -1)
    create_status=$(echo "$create_response" | tail -n 1)
    if [ "$create_status" -eq 201 ]; then
        self_serve_tenant_id=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
        role_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
            "SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id \
             JOIN users u ON u.id = ur.user_id \
             WHERE ur.tenant_id = '$self_serve_tenant_id' AND r.name = 'TENANT_ADMIN' \
               AND u.email IS NOT NULL AND ur.deleted_at IS NULL;" | tr -d ' \n')
        if [ "$role_count" = "1" ]; then
            echo "PASS: Regular user self-serve create (HTTP 201, auto-assigned TENANT_ADMIN)"
            PASS=$((PASS + 1))
        else
            echo "FAIL: HTTP 201 but creator was not auto-assigned TENANT_ADMIN (role_count=$role_count)"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Regular user self-serve create — expected HTTP 201, got HTTP $create_status"
        echo "Body: $create_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Regular user self-serve create (no regular token)"
fi

# Test 8: The same user can create a SECOND tenant too — one person, multiple companies.
echo ""
echo "--- Test 8: Same regular user creates a second tenant (multi-tenant membership) ---"
if [ -n "$REGULAR_TOKEN" ]; then
    run_test "Regular user creates a second tenant" 201 \
        -X POST "$BASE_URL/api/v1/tenants" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $REGULAR_TOKEN" \
        -d "{\"name\":\"Second Corp\",\"slug\":\"second-corp-$(date +%s)\"}"
else
    echo "SKIP: Second tenant (no regular token)"
fi

# Test 9: Platform Admin still gets old behavior — no auto-membership assigned to the admin
echo ""
echo "--- Test 9: Platform Admin create → no auto TENANT_ADMIN membership for the admin ---"
admin_role_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id \
     JOIN users u ON u.id = ur.user_id \
     WHERE ur.tenant_id = '$tenant_id' AND r.name = 'TENANT_ADMIN' AND u.email = 'admin@fams.com' \
       AND ur.deleted_at IS NULL;" | tr -d ' \n')
if [ "$admin_role_count" = "0" ]; then
    echo "PASS: Platform Admin not auto-assigned membership in tenant it created ($tenant_id)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected no auto-membership for Platform Admin, found $admin_role_count row(s)"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
