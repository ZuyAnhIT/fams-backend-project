#!/usr/bin/env bash
# Tests for PATCH /api/v1/tenants/{id} (update tenant profile)
# Usage: BASE_URL=http://localhost:8080 bash test_update_tenant.sh
#
# Owner-only by product decision (24/07/2026), EXCEPT Platform Admin (exemption added
# 2026-08-14, docs/reviews/backend/rbac-role-permission-audit-2026-08-13.md muc 6): whoever
# provisions a tenant does NOT retain edit rights afterward unless they are a Platform Admin —
# regular non-owner users are still forbidden, but Platform Admin can always update a tenant's
# profile (support cases where the owner lost account access entirely). Matches how every
# other sensitive tenant resource already treats Platform Admin (IP whitelist, tenant
# settings). See TenantService.updateTenant.

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

echo "=== Tenant Update Tests ==="
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
echo "Admin token obtained."

# Register the tenant's owner (must already exist — platform-admin creation assigns a
# direct owner, not an invitation) and a separate regular (non-owner) user for Test 6.
TS=$(date +%s)
OWNER_EMAIL="update_tenant_owner_${TS}@fams.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/register" -H "Content-Type: application/json" \
    -d "{\"email\":\"$OWNER_EMAIL\",\"password\":\"TestPass1\",\"displayName\":\"Update Test Owner\"}"
docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
    "UPDATE users SET email_verified = true WHERE email = '$OWNER_EMAIL';" > /dev/null
OWNER_LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$OWNER_EMAIL\",\"password\":\"TestPass1\"}")
OWNER_TOKEN=$(echo "$OWNER_LOGIN" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Owner token obtained."

REGULAR_TOKEN=$(register_verified_test_user_token "$BASE_URL" "Regular")
echo "Regular user token obtained."

# Create a tenant to update, assigned to OWNER_EMAIL
create_body=$(curl -s \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Update Test Corp\",\"slug\":\"update-test-$TS\",\"ownerEmail\":\"$OWNER_EMAIL\"}")

TENANT_ID=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$TENANT_ID" ]; then
    echo "SETUP FAILED: Could not create tenant for update tests"
    echo "Body: $create_body"
    exit 1
fi
echo "Test tenant created: id=$TENANT_ID owner=$OWNER_EMAIL"
echo ""

# Test 1: Happy path — the OWNER updates their own tenant
echo "--- Test 1: Happy path (owner updates name and timezone) ---"
update_response=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $OWNER_TOKEN" \
    -d '{"name":"Updated Corp Name","timezone":"Asia/Ho_Chi_Minh","industry":"Finance","countryCode":"VN"}')

update_body=$(echo "$update_response" | head -n -1)
update_status=$(echo "$update_response" | tail -n 1)

if [ "$update_status" -eq 200 ]; then
    updated_name=$(echo "$update_body" | grep -o '"name":"[^"]*"' | head -1 | cut -d'"' -f4)
    updated_tz=$(echo "$update_body" | grep -o '"timezone":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ "$updated_name" = "Updated Corp Name" ] && [ "$updated_tz" = "Asia/Ho_Chi_Minh" ]; then
        echo "PASS: Happy path (HTTP 200, name and timezone updated correctly)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Happy path — values not updated (name=$updated_name tz=$updated_tz)"
        echo "Body: $update_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Happy path — expected HTTP 200, got HTTP $update_status"
    echo "Body: $update_body"
    FAIL=$((FAIL + 1))
fi

# Test 2: Partial update (only one field), still as owner
echo ""
echo "--- Test 2: Partial update (logoUrl only) ---"
partial_response=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $OWNER_TOKEN" \
    -d '{"logoUrl":"https://example.com/logo.png"}')

partial_body=$(echo "$partial_response" | head -n -1)
partial_status=$(echo "$partial_response" | tail -n 1)

if [ "$partial_status" -eq 200 ]; then
    logo=$(echo "$partial_body" | grep -o '"logoUrl":"[^"]*"' | head -1 | cut -d'"' -f4)
    name_still=$(echo "$partial_body" | grep -o '"name":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ "$logo" = "https://example.com/logo.png" ] && [ "$name_still" = "Updated Corp Name" ]; then
        echo "PASS: Partial update (HTTP 200, logoUrl updated, name unchanged)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Partial update — logo=$logo name=$name_still"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Partial update — expected HTTP 200, got HTTP $partial_status"
    FAIL=$((FAIL + 1))
fi

# Test 3: Validation error — name too short (as owner)
echo ""
echo "--- Test 3: Validation error (name too short) ---"
run_test "Name too short" 400 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $OWNER_TOKEN" \
    -d '{"name":"X"}'

# Test 4: Not found — non-existent tenant id (as owner; owner-check never reached)
echo ""
echo "--- Test 4: Not found ---"
FAKE_ID="00000000-0000-0000-0000-000000000000"
run_test "Not found" 404 \
    -X PATCH "$BASE_URL/api/v1/tenants/$FAKE_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $OWNER_TOKEN" \
    -d '{"name":"Ghost Corp"}'

# Test 5: Unauthenticated → 401
echo ""
echo "--- Test 5: Unauthenticated ---"
run_test "Unauthenticated" 401 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID" \
    -H "Content-Type: application/json" \
    -d '{"name":"No Auth Corp"}'

# Test 6: Regular user who is NOT the tenant owner → 403
echo ""
echo "--- Test 6: Forbidden (regular user is not tenant owner) ---"
run_test "Non-owner forbidden" 403 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $REGULAR_TOKEN" \
    -d '{"name":"Hacked Corp"}'

# Test 7: Platform Admin — including the admin who PROVISIONED this very tenant — IS allowed
# to edit it (exemption added 2026-08-14 for support cases where the owner lost account
# access entirely). Provisioning rights and ongoing edit rights both extend to Platform Admin,
# on top of the assigned owner — unlike a regular non-owner user (Test 6), which stays 403.
echo ""
echo "--- Test 7: Platform admin allowed (support-access exemption, 2026-08-14) ---"
run_test "Platform admin allowed" 200 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Admin Support Edit Corp"}'

# Test 8: Duplicate domain conflict → 409 (as owner)
echo ""
echo "--- Test 8: Duplicate domain conflict ---"
DOMAIN="unique-domain-$TS.example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Domain Corp\",\"slug\":\"domain-corp-$TS\",\"domain\":\"$DOMAIN\",\"ownerEmail\":\"$OWNER_EMAIL\"}"

run_test "Duplicate domain" 409 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $OWNER_TOKEN" \
    -d "{\"domain\":\"$DOMAIN\"}"

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
