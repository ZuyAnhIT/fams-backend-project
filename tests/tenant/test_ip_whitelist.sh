#!/usr/bin/env bash
# Tests for /api/v1/tenants/{id}/ip-whitelists (add, list, update, delete)
# Usage: BASE_URL=http://localhost:8080 bash test_ip_whitelist.sh

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

echo "=== IP Whitelist Tests ==="
echo "Target: $BASE_URL"
echo ""

# Login as platform admin
echo "--- Setup: Login as platform admin ---"
login_body=$(curl -s \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$ADMIN_TOKEN" ] && echo "SETUP FAILED: no admin token" && exit 1
echo "Admin token obtained."

# Register an existing user to be the tenant's owner (now required for platform-admin-
# provisioned tenants — see TenantService.createTenant, direct assignment not an invitation)
TS=$(date +%s)
OWNER_EMAIL="ip_whitelist_owner_${TS}@fams.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/register" -H "Content-Type: application/json" \
    -d "{\"email\":\"$OWNER_EMAIL\",\"password\":\"TestPass1\",\"displayName\":\"IP Whitelist Owner\"}"
docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
    "UPDATE users SET email_verified = true WHERE email = '$OWNER_EMAIL';" > /dev/null

# Create a tenant
create_body=$(curl -s \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"IP Test Corp\",\"slug\":\"ip-test-$TS\",\"ownerEmail\":\"$OWNER_EMAIL\"}")
TENANT_ID=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$TENANT_ID" ] && echo "SETUP FAILED: no tenant id" && exit 1
echo "Tenant created: id=$TENANT_ID"

# Register a regular (non-owner) user
REGULAR_TOKEN=$(register_verified_test_user_token "$BASE_URL" "Regular")
echo "Regular user token obtained."
echo ""

# Test 1: Add IPv4 entry (happy path)
echo "--- Test 1: Add IPv4 entry ---"
add_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/ip-whitelists" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"ipAddress":"192.168.1.140","label":"Office IP","applicableRoleNames":["TENANT_ADMIN"]}')
add_body=$(echo "$add_response" | head -n -1)
add_status=$(echo "$add_response" | tail -n 1)

if [ "$add_status" -eq 201 ]; then
    ENTRY_ID=$(echo "$add_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    ip=$(echo "$add_body" | grep -o '"ipAddress":"[^"]*"' | cut -d'"' -f4)
    has_role=$(echo "$add_body" | grep -c '"TENANT_ADMIN"' || true)
    if [ "$ip" = "192.168.1.140" ] && [ "$has_role" -ge 1 ] && [ -n "$ENTRY_ID" ]; then
        echo "PASS: Add IPv4 entry (HTTP 201, id=$ENTRY_ID ip=$ip applicableRoleNames=[TENANT_ADMIN])"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Add IPv4 entry — unexpected values ip=$ip has_role=$has_role"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Add IPv4 entry — expected HTTP 201, got HTTP $add_status"
    echo "Body: $add_body"
    FAIL=$((FAIL + 1))
    ENTRY_ID=""
fi

# Test 2: Add CIDR entry
echo ""
echo "--- Test 2: Add CIDR entry ---"
cidr_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/ip-whitelists" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"ipAddress":"10.0.0.0/24","label":"Internal network"}')
cidr_status=$(echo "$cidr_response" | tail -n 1)
if [ "$cidr_status" -eq 201 ]; then
    CIDR_ENTRY_ID=$(echo "$cidr_response" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "PASS: Add CIDR entry (HTTP 201, id=$CIDR_ENTRY_ID)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Add CIDR entry — expected HTTP 201, got HTTP $cidr_status"
    FAIL=$((FAIL + 1))
    CIDR_ENTRY_ID=""
fi

# Test 3: List entries — should return both
echo ""
echo "--- Test 3: List entries ---"
list_response=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/ip-whitelists" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list_body=$(echo "$list_response" | head -n -1)
list_status=$(echo "$list_response" | tail -n 1)

if [ "$list_status" -eq 200 ]; then
    count=$(echo "$list_body" | grep -o '"ipAddress"' | wc -l | tr -d ' ')
    if [ "$count" -ge 2 ]; then
        echo "PASS: List entries (HTTP 200, $count entries found)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: List entries — expected ≥2 entries, got $count"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: List entries — expected HTTP 200, got HTTP $list_status"
    FAIL=$((FAIL + 1))
fi

# Test 4: Update entry — change label and disable
echo ""
echo "--- Test 4: Update entry (disable and relabel) ---"
if [ -n "$ENTRY_ID" ]; then
    upd_response=$(curl -s -w "\n%{http_code}" \
        -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/ip-whitelists/$ENTRY_ID" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d '{"label":"Office (disabled)","isActive":false}')
    upd_body=$(echo "$upd_response" | head -n -1)
    upd_status=$(echo "$upd_response" | tail -n 1)

    if [ "$upd_status" -eq 200 ]; then
        active=$(echo "$upd_body" | grep -o '"isActive":[a-z]*' | cut -d: -f2 || true)
        label=$(echo "$upd_body" | grep -o '"label":"[^"]*"' | cut -d'"' -f4 || true)
        if [ "$active" = "false" ] && [ "$label" = "Office (disabled)" ]; then
            echo "PASS: Update entry (HTTP 200, isActive=$active label=$label)"
            PASS=$((PASS + 1))
        else
            echo "FAIL: Update entry — isActive=$active label=$label"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Update entry — expected HTTP 200, got HTTP $upd_status"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Update entry (no entry id from Test 1)"
fi

# Test 5: Delete entry
echo ""
echo "--- Test 5: Delete entry ---"
if [ -n "$CIDR_ENTRY_ID" ]; then
    run_test "Delete entry" 200 \
        -X DELETE "$BASE_URL/api/v1/tenants/$TENANT_ID/ip-whitelists/$CIDR_ENTRY_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN"

    # Confirm deleted entry no longer appears in list
    list2_body=$(curl -s \
        -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/ip-whitelists" \
        -H "Authorization: Bearer $ADMIN_TOKEN")
    count2=$(echo "$list2_body" | grep -o '"id":"[^"]*"' | wc -l | tr -d ' ')
    if [ "$count2" -lt "$count" ]; then
        echo "PASS: Deleted entry removed from list ($count → $count2 entries)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Deleted entry still appears in list (count=$count2)"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Delete entry (no CIDR entry id)"
fi

# Test 6: Invalid IP format → 400
echo ""
echo "--- Test 6: Invalid IP format ---"
run_test "Invalid IP format" 400 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/ip-whitelists" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"ipAddress":"not-an-ip"}'

# Test 7: Unknown role name → 400
echo ""
echo "--- Test 7: Unknown role name ---"
run_test "Unknown role name" 400 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/ip-whitelists" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"ipAddress":"1.2.3.4","applicableRoleNames":["NOT_A_REAL_ROLE"]}'

# Test 8: Missing ipAddress → 400
echo ""
echo "--- Test 8: Missing ipAddress ---"
run_test "Missing ipAddress" 400 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/ip-whitelists" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"label":"No IP"}'

# Test 9: Tenant not found → 404
echo ""
echo "--- Test 9: Tenant not found ---"
FAKE_ID="00000000-0000-0000-0000-000000000000"
run_test "Tenant not found" 404 \
    -X GET "$BASE_URL/api/v1/tenants/$FAKE_ID/ip-whitelists" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Test 10: Entry not found → 404
echo ""
echo "--- Test 10: Entry not found ---"
run_test "Entry not found" 404 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/ip-whitelists/$FAKE_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"label":"ghost"}'

# Test 11: Unauthenticated → 401
echo ""
echo "--- Test 11: Unauthenticated ---"
run_test "Unauthenticated list" 401 \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/ip-whitelists"

run_test "Unauthenticated add" 401 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/ip-whitelists" \
    -H "Content-Type: application/json" \
    -d '{"ipAddress":"1.2.3.4"}'

# Test 12: Non-owner regular user → 403
echo ""
echo "--- Test 12: Forbidden (regular user) ---"
run_test "Non-owner forbidden" 403 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/ip-whitelists" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $REGULAR_TOKEN" \
    -d '{"ipAddress":"1.2.3.4"}'

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
