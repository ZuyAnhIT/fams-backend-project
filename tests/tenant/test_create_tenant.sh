#!/usr/bin/env bash
# Tests for POST /api/v1/tenants (create tenant)
# Usage: BASE_URL=http://localhost:8080 bash test_create_tenant.sh
#
# Two creation modes as of 24/07/2026 (see TenantService.createTenant):
#   - Self-service: any authenticated user creates their OWN tenant, becomes its
#     TENANT_ADMIN, starts on the default trial plan. ownerUserId/ownerEmail/planId
#     must be omitted (403 if a self-serve caller tries to set them).
#   - Platform provisioning (Platform Admin, or Platform Staff with tenants:create):
#     MUST supply ownerUserId/ownerEmail identifying an EXISTING FAMS user — this is a
#     direct assignment, not an invitation (404 if that account doesn't exist). The
#     provisioning caller is never made a member. Tenant starts active, may pick a plan
#     via planId.

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
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')

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

# Register a regular user — used both as the self-serve creator AND as the existing
# account the admin will assign as owner in the platform-provisioning tests.
echo "--- Setup: Register a regular user ---"
TS=$(date +%s)
REGULAR_EMAIL="create_tenant_owner_${TS}@fams.com"
REGULAR_TOKEN=$(register_verified_test_user_token "$BASE_URL" "Regular User" "$REGULAR_EMAIL")
if [ -n "$REGULAR_TOKEN" ]; then
    echo "Regular user token obtained."
else
    echo "SETUP WARNING: Could not register regular user — skipping self-serve tests"
fi
echo ""

# Use a unique slug per run to avoid collisions between test runs
UNIQUE_SLUG="test-tenant-$(date +%s)"

# Test 1: Platform admin create WITHOUT an owner → 400 (owner is now required)
echo "--- Test 1: Platform admin create without owner → 400 ---"
run_test "Admin create without owner rejected" 400 \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Test Corp\",\"slug\":\"${UNIQUE_SLUG}-noowner\"}"

# Test 2: Platform admin create WITH an existing owner → 201
echo ""
echo "--- Test 2: Platform admin create with existing owner (happy path) ---"
create_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Test Corp\",\"slug\":\"$UNIQUE_SLUG\",\"industry\":\"Tech\",\"timezone\":\"Asia/Ho_Chi_Minh\",\"locale\":\"vi\",\"currencyCode\":\"VND\",\"ownerEmail\":\"$REGULAR_EMAIL\"}")

create_body=$(echo "$create_response" | head -n -1)
create_status=$(echo "$create_response" | tail -n 1)

tenant_id=""
if [ "$create_status" -eq 201 ]; then
    tenant_id=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    owner_id_in_body=$(echo "$create_body" | grep -o '"ownerId":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ -n "$tenant_id" ] && [ -n "$owner_id_in_body" ]; then
        echo "PASS: Admin create with owner (HTTP 201, id=$tenant_id, ownerId=$owner_id_in_body)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: HTTP 201 but tenant id or ownerId missing in response"
        echo "Body: $create_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Admin create with owner — expected HTTP 201, got HTTP $create_status"
    echo "Body: $create_body"
    FAIL=$((FAIL + 1))
fi

# Test 3: Admin create with a non-existent owner email → 404 (not an invitation)
echo ""
echo "--- Test 3: Owner email with no existing account → 404 ---"
run_test "Non-existent owner rejected" 404 \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Ghost Owner Corp\",\"slug\":\"ghost-owner-$TS\",\"ownerEmail\":\"nobody-$TS@nowhere.example\"}"

# Test 4: Duplicate slug → 409
echo ""
echo "--- Test 4: Duplicate slug ---"
run_test "Duplicate slug" 409 \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Another Corp\",\"slug\":\"$UNIQUE_SLUG\",\"ownerEmail\":\"$REGULAR_EMAIL\"}"

# Test 5: Missing name → 400
echo ""
echo "--- Test 5: Missing name ---"
run_test "Missing name" 400 \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"slug\":\"some-slug-no-name\",\"ownerEmail\":\"$REGULAR_EMAIL\"}"

# Test 6: Missing slug → 400
echo ""
echo "--- Test 6: Missing slug ---"
run_test "Missing slug" 400 \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Some Corp\",\"ownerEmail\":\"$REGULAR_EMAIL\"}"

# Test 7: Invalid slug format (uppercase) → 400
echo ""
echo "--- Test 7: Invalid slug format ---"
run_test "Invalid slug format" 400 \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Bad Slug Corp\",\"slug\":\"UPPERCASE_SLUG\",\"ownerEmail\":\"$REGULAR_EMAIL\"}"

# Test 8: Unauthenticated → 401
echo ""
echo "--- Test 8: Unauthenticated ---"
run_test "Unauthenticated" 401 \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -d '{"name":"Ghost Corp","slug":"ghost-corp"}'

# Test 9: Regular (self-serve) user can create their OWN tenant → 201 + auto TENANT_ADMIN
echo ""
echo "--- Test 9: Regular user self-serve create → 201 + auto TENANT_ADMIN ---"
SELF_SERVE_SLUG="self-serve-$(date +%s)"
self_serve_tenant_id=""
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

# Test 10: Self-serve caller may NOT assign an owner or plan for themselves → 403
echo ""
echo "--- Test 10: Self-serve caller cannot set ownerUserId/planId → 403 ---"
if [ -n "$REGULAR_TOKEN" ]; then
    run_test "Self-serve owner/plan escalation rejected" 403 \
        -X POST "$BASE_URL/api/v1/tenants" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $REGULAR_TOKEN" \
        -d "{\"name\":\"Sneaky Corp\",\"slug\":\"sneaky-$(date +%s)\",\"ownerEmail\":\"$REGULAR_EMAIL\"}"
else
    echo "SKIP: no regular token"
fi

# Test 11: The same user can create a SECOND tenant too — one person, multiple companies.
echo ""
echo "--- Test 11: Same regular user creates a second tenant (multi-tenant membership) ---"
if [ -n "$REGULAR_TOKEN" ]; then
    run_test "Regular user creates a second tenant" 201 \
        -X POST "$BASE_URL/api/v1/tenants" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $REGULAR_TOKEN" \
        -d "{\"name\":\"Second Corp\",\"slug\":\"second-corp-$(date +%s)\"}"
else
    echo "SKIP: Second tenant (no regular token)"
fi

# Test 12: Platform Admin who provisioned a tenant gets NO membership in it — only the
# assigned owner (REGULAR_EMAIL, from Test 2) does.
echo ""
echo "--- Test 12: Platform Admin provisioning → no membership for the admin, owner has it ---"
if [ -n "$tenant_id" ]; then
    admin_role_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id \
         JOIN users u ON u.id = ur.user_id \
         WHERE ur.tenant_id = '$tenant_id' AND r.name = 'TENANT_ADMIN' AND u.email = 'admin@fams.com' \
           AND ur.deleted_at IS NULL;" | tr -d ' \n')
    owner_role_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id \
         JOIN users u ON u.id = ur.user_id \
         WHERE ur.tenant_id = '$tenant_id' AND r.name = 'TENANT_ADMIN' AND u.email = '$REGULAR_EMAIL' \
           AND ur.deleted_at IS NULL;" | tr -d ' \n')
    if [ "$admin_role_count" = "0" ] && [ "$owner_role_count" = "1" ]; then
        echo "PASS: Admin has no membership ($tenant_id); assigned owner has TENANT_ADMIN"
        PASS=$((PASS + 1))
    else
        echo "FAIL: admin_role_count=$admin_role_count (want 0), owner_role_count=$owner_role_count (want 1)"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: no tenant_id from Test 2"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
